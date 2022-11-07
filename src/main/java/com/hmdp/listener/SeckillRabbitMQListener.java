package com.hmdp.listener;


import cn.hutool.core.util.StrUtil;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Component
public class SeckillRabbitMQListener implements ChannelAwareMessageListener {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Override
    @RabbitListener(queues = "order_seckill_queue")
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            String jsonMessage = new String(message.getBody());
            if (!(StrUtil.isNotBlank(jsonMessage))) {
                channel.basicAck(deliveryTag,true);
                return ;
            }
            VoucherOrder voucherOrder = JSONUtil.toBean(jsonMessage, VoucherOrder.class);

            voucherOrderService.createVoucherOrder(voucherOrder);

            channel.basicAck(deliveryTag,true);
        } catch (IOException e) {
            e.printStackTrace();
            channel.basicNack(deliveryTag,true,true);
        }


    }
}
