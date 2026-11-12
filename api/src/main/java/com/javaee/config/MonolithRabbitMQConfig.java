package com.javaee.config;

import com.javaee.agentservice.config.AiRabbitMQConfig;
import com.javaee.fileservice.config.RabbitMQConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonolithRabbitMQConfig {

    @Bean
    public TopicExchange fileExchange() {
        return new TopicExchange(RabbitMQConfig.FILE_EXCHANGE, true, false);
    }

    @Bean
    public Queue fileUploadQueue() {
        return new Queue(RabbitMQConfig.FILE_UPLOAD_QUEUE, true);
    }

    @Bean
    public Queue fileDownloadQueue() {
        return new Queue(RabbitMQConfig.FILE_DOWNLOAD_QUEUE, true);
    }

    @Bean
    public Queue fileProcessQueue() {
        return new Queue(RabbitMQConfig.FILE_PROCESS_QUEUE, true);
    }

    @Bean
    public Queue fileDeleteQueue() {
        return new Queue(RabbitMQConfig.FILE_DELETE_QUEUE, true);
    }

    @Bean
    public Binding fileUploadBinding(
            @Qualifier("fileUploadQueue") Queue fileUploadQueue,
            @Qualifier("fileExchange") TopicExchange fileExchange) {
        return BindingBuilder.bind(fileUploadQueue)
                .to(fileExchange)
                .with(RabbitMQConfig.FILE_UPLOAD_ROUTING_KEY);
    }

    @Bean
    public Binding fileDownloadBinding(
            @Qualifier("fileDownloadQueue") Queue fileDownloadQueue,
            @Qualifier("fileExchange") TopicExchange fileExchange) {
        return BindingBuilder.bind(fileDownloadQueue)
                .to(fileExchange)
                .with(RabbitMQConfig.FILE_DOWNLOAD_ROUTING_KEY);
    }

    @Bean
    public Binding fileProcessBinding(
            @Qualifier("fileProcessQueue") Queue fileProcessQueue,
            @Qualifier("fileExchange") TopicExchange fileExchange) {
        return BindingBuilder.bind(fileProcessQueue)
                .to(fileExchange)
                .with(RabbitMQConfig.FILE_PROCESS_ROUTING_KEY);
    }

    @Bean
    public Binding fileDeleteBinding(
            @Qualifier("fileDeleteQueue") Queue fileDeleteQueue,
            @Qualifier("fileExchange") TopicExchange fileExchange) {
        return BindingBuilder.bind(fileDeleteQueue)
                .to(fileExchange)
                .with(RabbitMQConfig.FILE_DELETE_ROUTING_KEY);
    }

    @Bean
    public TopicExchange aiExchange() {
        return new TopicExchange(AiRabbitMQConfig.AI_EXCHANGE, true, false);
    }

    @Bean
    public Queue aiModelRequestQueue() {
        return new Queue(AiRabbitMQConfig.AI_MODEL_REQUEST_QUEUE, true);
    }

    @Bean
    public Binding aiModelRequestBinding(
            @Qualifier("aiModelRequestQueue") Queue aiModelRequestQueue,
            @Qualifier("aiExchange") TopicExchange aiExchange) {
        return BindingBuilder.bind(aiModelRequestQueue)
                .to(aiExchange)
                .with(AiRabbitMQConfig.AI_MODEL_REQUEST_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            @Qualifier("jsonMessageConverter") MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
