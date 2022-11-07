package com.hmdp.config;


import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "order_seckill_exchange";
    public static final String QUEUE_NAME = "order_seckill_queue";


    @Bean("orderExchange")
    public Exchange orderExchange(){
        return ExchangeBuilder.topicExchange(EXCHANGE_NAME).durable(true).build();
    }

    @Bean("orderQueue")
    public Queue orderQueue(){
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    @Bean
    public Binding bindQueueExchange(@Qualifier("orderExchange") Exchange exchange,@Qualifier("orderQueue") Queue queue ){
        return BindingBuilder.bind(queue).to(exchange).with("seckill.#").noargs();
    }



}
