package com.dianping.puma.alarm.service.memory;

import com.dianping.puma.alarm.service.PumaWeChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by xiaotian.li on 16/3/23.
 * Email: lixiaotian07@gmail.com
 */
public class MemoryWeChatService implements PumaWeChatService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void send(String recipients, String message) {
        logger.info("Send weChat message[{}] to recipient[{}].", message, recipients);
    }
}