/**
 * Project: ${puma-server.aid}
 *
 * File Created at 2012-6-6 $Id$
 *
 * Copyright 2010 dianping.com. All rights reserved.
 *
 * This software is the confidential and proprietary information of Dianping
 * Company. ("Confidential Information"). You shall not disclose such
 * Confidential Information and shall use it only in accordance with the terms
 * of the license agreement you entered into with dianping.com.
 */
package com.dianping.puma.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import com.dianping.puma.bo.PumaContext;
import com.dianping.puma.core.entity.PumaTask;
import com.dianping.puma.core.model.BinlogInfo;
import com.dianping.puma.core.model.BinlogStat;
import org.apache.commons.lang.StringUtils;

import com.dianping.puma.common.SystemStatusContainer;
import com.dianping.puma.core.annotation.ThreadUnSafe;
import com.dianping.puma.core.event.ChangedEvent;
import com.dianping.puma.core.event.DdlEvent;
import com.dianping.puma.core.event.RowChangedEvent;
import com.dianping.puma.datahandler.DataHandlerResult;
import com.dianping.puma.parser.mysql.BinlogConstanst;
import com.dianping.puma.parser.mysql.event.BinlogEvent;
import com.dianping.puma.parser.mysql.event.RotateEvent;
import com.dianping.puma.parser.mysql.packet.AuthenticatePacket;
import com.dianping.puma.parser.mysql.packet.BinlogPacket;
import com.dianping.puma.parser.mysql.packet.ComBinlogDumpPacket;
import com.dianping.puma.parser.mysql.packet.OKErrorPacket;
import com.dianping.puma.parser.mysql.packet.PacketFactory;
import com.dianping.puma.parser.mysql.packet.PacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于MySQL复制机制的Server
 *
 * @author Leo Liang
 */
@ThreadUnSafe
public class DefaultTaskExecutor extends AbstractTaskExecutor {

	private static final Logger LOG = LoggerFactory.getLogger(DefaultTaskExecutor.class);

	private int port = 3306;

	private String dbHost;

	private String dbUsername;

	private long dbServerId;

	private String dbPassword;

	private String database;

	private String encoding = "utf-8";

	private Socket pumaSocket;

	private InputStream is;

	private OutputStream os;

	private BinlogInfo binlogInfo;

	private BinlogStat binlogStat;

	@Override
	public void doStart() throws Exception {

		long failCount = 0;
		do {
			try {
				// 读position/file文件
				BinlogInfo binlogInfo = binlogInfoHolder.getBinlogInfo(getContext().getPumaServerName());
				if (binlogInfo == null) {
					binlogInfo = new BinlogInfo();
					binlogInfo.setBinlogFile(getContext().getBinlogFileName());
					binlogInfo.setBinlogPosition(getContext().getBinlogStartPos());
				}

				getContext().setBinlogFileName(binlogInfo.getBinlogFile());
				getContext().setBinlogStartPos(binlogInfo.getBinlogPosition());
				getContext().setDBServerId(dbServerId);
				getContext().setMasterUrl(dbHost, port);

				setBinlogInfo(binlogInfo);

				SystemStatusContainer.instance.updateServerStatus(getTaskId(), dbHost, port, database, getContext()
						.getBinlogFileName(), getContext().getBinlogStartPos());

				connect();

				if (auth()) {
					LOG.info("Server logined... serverId: " + getServerId() + " host: " + dbHost + " port: " + port
							+ " username: " + dbUsername + " database: " + database + " dbServerId: " + getDbServerId());

					if (dumpBinlog()) {
						LOG.info("Dump binlog command success.");
						processBinlog();
					} else {
						throw new IOException("Dump binlog failed.");
					}
				} else {
					throw new IOException("Login failed.");
				}
			} catch (Throwable e) {
				if (isStop()) {
					return;
				}
				if (++failCount % 3 == 0) {
					this.notifyService.alarm("[" + getContext().getPumaServerName() + "]" + "Failed to dump mysql["
							+ dbHost + ":" + port + "] for 3 times.", e, true);
					failCount = 0;
				}
				LOG.error(
						"Exception occurs. serverId: " + getServerId() + " dbServerId: " + getDbServerId() + ". Reconnect...",
						e);
				Thread.sleep(((failCount % 10) + 1) * 2000);
			}
		} while (!isStop());

	}

	private void processBinlog() throws IOException {
		while (!isStop()) {
			// only slow down parsing, not stop
			if (SystemStatusContainer.instance.isStopTheWorld(this.getName())) {
				try {
					TimeUnit.SECONDS.sleep(1);
				} catch (InterruptedException e) {
					//ignore
				}
			}

			BinlogPacket binlogPacket = (BinlogPacket) PacketFactory.parsePacket(is, PacketType.BINLOG_PACKET,
					getContext());
			if (!binlogPacket.isOk()) {
				LOG.error("Binlog packet response error.");
				throw new IOException("Binlog packet response error.");
			} else {
				processBinlogPacket(binlogPacket);
			}

		}
	}

	protected void processBinlogPacket(BinlogPacket binlogPacket) throws IOException {
		BinlogEvent binlogEvent = parser.parse(binlogPacket.getBinlogBuf(), getContext());

		if (binlogEvent.getHeader().getEventType() != BinlogConstanst.FORMAT_DESCRIPTION_EVENT) {
			getContext().setNextBinlogPos(binlogEvent.getHeader().getNextPosition());
		}
		if (binlogEvent.getHeader().getEventType() == BinlogConstanst.ROTATE_EVENT) {
			processRotateEvent(binlogEvent);
		} else {
			processDataEvent(binlogEvent);
		}
	}

	protected void processDataEvent(BinlogEvent binlogEvent) {
		DataHandlerResult dataHandlerResult = null;
		// 一直处理一个binlogEvent的多行，处理完每行马上分发，以防止一个binlogEvent包含太多ChangedEvent而耗费太多内存
		do {
			dataHandlerResult = dataHandler.process(binlogEvent, getContext());
			if (dataHandlerResult != null && !dataHandlerResult.isEmpty()) {
				ChangedEvent changedEvent = dataHandlerResult.getData();

				updateOpsCounter(changedEvent);

				dispatch(changedEvent);
			}
		} while (dataHandlerResult != null && !dataHandlerResult.isFinished());

		if (binlogEvent.getHeader().getEventType() != BinlogConstanst.FORMAT_DESCRIPTION_EVENT) {
			getContext().setBinlogStartPos(binlogEvent.getHeader().getNextPosition());
		}

		setBinlogInfo(new BinlogInfo(getBinlogInfo().getBinlogFile(), binlogEvent.getHeader().getNextPosition()));

		// status report
		SystemStatusContainer.instance.updateServerStatus(getTaskId(), dbHost, port, database, getContext()
				.getBinlogFileName(), getContext().getBinlogStartPos());

		// 只有整个binlogEvent分发完了才save
		if (binlogEvent.getHeader() != null
				&& binlogEvent.getHeader().getNextPosition() != 0
				&& StringUtils.isNotBlank(getContext().getBinlogFileName())
				&& dataHandlerResult != null
				&& !dataHandlerResult.isEmpty()
				&& (dataHandlerResult.getData() instanceof DdlEvent || (
				dataHandlerResult.getData() instanceof RowChangedEvent && ((RowChangedEvent) dataHandlerResult
						.getData()).isTransactionCommit()))) {
			// save position
			binlogInfoHolder.setBinlogInfo(getTaskId(),
					new BinlogInfo(getContext().getBinlogFileName(), binlogEvent.getHeader().getNextPosition()));
		}
	}

	protected void dispatch(ChangedEvent changedEvent) {
		try {
			dispatcher.dispatch(changedEvent, getContext());
		} catch (Exception e) {
			this.notifyService.alarm("[" + getContext().getPumaServerName() + "]" + "Dispatch event failed. event("
					+ changedEvent + ")", e, true);
			LOG.error("Dispatcher dispatch failed.", e);
		}
	}

	protected void updateOpsCounter(ChangedEvent changedEvent) {
		// 增加行变更计数器(除去ddl事件和事务信息事件)
		if ((changedEvent instanceof RowChangedEvent) && !((RowChangedEvent) changedEvent).isTransactionBegin()
				&& !((RowChangedEvent) changedEvent).isTransactionCommit()) {
			switch (((RowChangedEvent) changedEvent).getActionType()) {
			case RowChangedEvent.INSERT:
				incrRowsInsert();
				SystemStatusContainer.instance.incServerRowInsertCounter(getTaskId());
				break;
			case RowChangedEvent.UPDATE:
				incrRowsUpdate();
				SystemStatusContainer.instance.incServerRowUpdateCounter(getTaskId());
				break;
			case RowChangedEvent.DELETE:
				incrRowsDelete();
				SystemStatusContainer.instance.incServerRowDeleteCounter(getTaskId());
				break;
			default:
				break;
			}
		} else if (changedEvent instanceof DdlEvent) {
			incrDdls();
			SystemStatusContainer.instance.incServerDdlCounter(getTaskId());
		}
	}

	protected void processRotateEvent(BinlogEvent binlogEvent) {
		RotateEvent rotateEvent = (RotateEvent) binlogEvent;
		binlogInfoHolder.setBinlogInfo(getTaskName(),
				new BinlogInfo(rotateEvent.getNextBinlogFileName(), rotateEvent.getFirstEventPosition()));
		getContext().setBinlogFileName(rotateEvent.getNextBinlogFileName());
		getContext().setBinlogStartPos(rotateEvent.getFirstEventPosition());

		setBinlogInfo(new BinlogInfo(rotateEvent.getNextBinlogFileName(), rotateEvent.getFirstEventPosition()));
		// status report
		SystemStatusContainer.instance.updateServerStatus(getTaskId(), dbHost, port, database, getContext()
				.getBinlogFileName(), getContext().getBinlogStartPos());
	}

	/**
	 * Connect to mysql master and parse the greeting packet
	 *
	 * @throws IOException
	 */
	private void connect() throws IOException {
		closeTransport();
		this.pumaSocket = new Socket();
		this.pumaSocket.setTcpNoDelay(false);
		this.pumaSocket.setKeepAlive(true);
		this.pumaSocket.connect(new InetSocketAddress(dbHost, port));
		is = new BufferedInputStream(pumaSocket.getInputStream());
		os = new BufferedOutputStream(pumaSocket.getOutputStream());
		PacketFactory.parsePacket(is, PacketType.CONNECT_PACKET, getContext());
	}

	/**
	 * Send COM_BINLOG_DUMP packet to mysql master and parse the response
	 *
	 * @return
	 * @throws IOException
	 */
	private boolean dumpBinlog() throws IOException {
		ComBinlogDumpPacket dumpBinlogPacket = (ComBinlogDumpPacket) PacketFactory.createCommandPacket(
				PacketType.COM_BINLOG_DUMP_PACKET, getContext());
		dumpBinlogPacket.setBinlogFileName(getContext().getBinlogFileName());
		dumpBinlogPacket.setBinlogFlag(0);
		dumpBinlogPacket.setBinlogPosition(getContext().getBinlogStartPos());
		dumpBinlogPacket.setServerId(getDbServerId());
		dumpBinlogPacket.buildPacket(getContext());

		dumpBinlogPacket.write(os, getContext());

		OKErrorPacket dumpCommandResultPacket = (OKErrorPacket) PacketFactory.parsePacket(is,
				PacketType.OKERROR_PACKET, getContext());
		if (dumpCommandResultPacket.isOk()) {
			if (StringUtils.isBlank(getContext().getBinlogFileName())
					&& StringUtils.isNotBlank(dumpCommandResultPacket.getMessage())) {
				String msg = dumpCommandResultPacket.getMessage();
				int startPos = msg.lastIndexOf(' ');
				if (startPos != -1) {
					startPos += 1;
				} else {
					startPos = 0;
				}
				String binlogFile = dumpCommandResultPacket.getMessage().substring(startPos);
				binlogInfoHolder
						.setBinlogInfo(getTaskName(), new BinlogInfo(binlogFile, getContext().getBinlogStartPos()));
				getContext().setBinlogFileName(binlogFile);
			}
			return true;
		} else {
			LOG.error("Dump binlog failed. Reason: " + dumpCommandResultPacket.getMessage());
			return false;
		}
	}

	/**
	 * Send Authentication Packet to mysql master and parse the response
	 *
	 * @return
	 * @throws IOException
	 */
	private boolean auth() throws IOException {
		// auth
		AuthenticatePacket authPacket = (AuthenticatePacket) PacketFactory.createCommandPacket(
				PacketType.AUTHENTICATE_PACKET, getContext());

		authPacket.setPassword(dbPassword);
		authPacket.setUser(dbUsername);
		authPacket.setDatabase(database);
		authPacket.buildPacket(getContext());
		authPacket.write(os, getContext());

		OKErrorPacket okErrorPacket = (OKErrorPacket) PacketFactory.parsePacket(is, PacketType.OKERROR_PACKET,
				getContext());

		if (okErrorPacket.isOk()) {
			return true;
		} else {
			LOG.error("Login failed. Reason: " + okErrorPacket.getMessage());
			return false;
		}
	}

	protected void doStop() throws Exception {
		closeTransport();
	}

	private void closeTransport() {
		try {
			if (this.is != null) {
				this.is.close();
			}
		} catch (IOException ioEx) {
			LOG.warn("Server " + this.getTaskName() + " failed to close the inputstream.");
		} finally {
			this.is = null;
		}
		try {
			if (this.os != null) {
				this.os.close();
			}
		} catch (IOException ioEx) {
			LOG.warn("Server " + this.getTaskName() + " failed to close the outputstream");
		} finally {
			this.os = null;
		}
		try {
			if (this.pumaSocket != null) {
				this.pumaSocket.close();
			}
		} catch (IOException ioEx) {
			LOG.warn("Server " + this.getTaskName() + " failed to close the socket", ioEx);
		} finally {
			this.pumaSocket = null;
		}
	}

	public void initContext() {
		PumaContext context = new PumaContext();

		BinlogInfo binlogInfo = binlogInfoHolder.getBinlogInfo(this.getTaskId());


		if (binlogInfo == null) {
			context.setBinlogStartPos(this.getBinlogInfo().getBinlogPosition());
			context.setBinlogFileName(this.getBinlogInfo().getBinlogFile());
		} else {
			context.setBinlogFileName(binlogInfo.getBinlogFile());
			context.setBinlogStartPos(binlogInfo.getBinlogPosition());
		}

		//context.setPumaServerId(taskExecutor.getServerId());
		context.setPumaServerName(this.getTaskName());
		this.setContext(context);
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getDBHost() {
		return dbHost;
	}

	public void setDBHost(String dbHost) {
		this.dbHost = dbHost;
	}

	public String getDBUsername() {
		return dbUsername;
	}

	public void setDBUsername(String dbUsername) {
		this.dbUsername = dbUsername;
	}

	public String getDBPassword() {
		return dbPassword;
	}

	public void setDBPassword(String dbPassword) {
		this.dbPassword = dbPassword;
	}

	public String getEncoding() {
		return encoding;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	/**
	 * @return the database
	 */
	public String getDatabase() {
		return database;
	}

	/**
	 * @param database the database to set
	 */
	public void setDatabase(String database) {
		this.database = database;
	}

	/**
	 * @return the dbServerId
	 */
	public long getDbServerId() {
		return dbServerId;
	}

	/**
	 * @param dbServerId the dbServerId to set
	 */
	public void setDbServerId(long dbServerId) {
		this.dbServerId = dbServerId;
	}

	public BinlogInfo getBinlogInfo() {
		return this.binlogInfo;
	}

	public void setBinlogInfo(BinlogInfo binlogInfo) {
		this.binlogInfo = binlogInfo;
	}

	public BinlogStat getBinlogStat() {
		return this.binlogStat;
	}

	public void setBinlogStat(BinlogStat binlogStat) {
		this.binlogStat = binlogStat;
	}

	public void incrRowsInsert() {
		Long rowsInsert = this.binlogStat.getRowsInsert();
		binlogStat.setRowsInsert(rowsInsert + 1);
	}

	public void incrRowsUpdate() {
		Long rowsUpdate = this.binlogStat.getRowsUpdate();
		binlogStat.setRowsUpdate(rowsUpdate + 1);
	}

	public void incrRowsDelete() {
		Long rowsDelete = this.binlogStat.getRowsDelete();
		binlogStat.setRowsDelete(rowsDelete + 1);
	}

	public void incrDdls() {
		Long ddls = this.binlogStat.getDdls();
		binlogStat.setDdls(ddls + 1);
	}
}