package com.javaee.fileservice.config;

/**
 * File messaging destinations shared by the unified RabbitMQ configuration.
 */
public final class RabbitMQConfig {

    public static final String FILE_UPLOAD_QUEUE = "file.upload.queue";
    public static final String FILE_DOWNLOAD_QUEUE = "file.download.queue";
    public static final String FILE_PROCESS_QUEUE = "file.process.queue";
    public static final String FILE_DELETE_QUEUE = "file.delete.queue";

    public static final String FILE_EXCHANGE = "file.exchange";

    public static final String FILE_UPLOAD_ROUTING_KEY = "file.upload";
    public static final String FILE_DOWNLOAD_ROUTING_KEY = "file.download";
    public static final String FILE_PROCESS_ROUTING_KEY = "file.process";
    public static final String FILE_DELETE_ROUTING_KEY = "file.delete";

    private RabbitMQConfig() {
    }
}
