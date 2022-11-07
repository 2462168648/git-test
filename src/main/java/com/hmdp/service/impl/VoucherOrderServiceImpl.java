package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.utils.RedisIdWorker;

import com.hmdp.utils.UserHolder;
import org.redisson.Redisson;
import org.redisson.api.RLock;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;


import javax.annotation.Resource;
import java.time.Duration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Redisson redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


private class VoucherOrderHandler implements Runnable {

    @Override
    public void run() {
        while (true) {
            try {
                // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明没有消息，继续下一次循环
                    continue;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                createVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理订单异常", e);
                handlePendingList();
            }
        }
    }
private void handlePendingList() {
    while (true) {
        try {
            // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
            List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create("stream.orders", ReadOffset.from("0"))
            );
            // 2.判断订单信息是否为空
            if (list == null || list.isEmpty()) {
                // 如果为null，说明没有异常消息，结束循环
                break;
            }
            // 解析数据
            MapRecord<String, Object, Object> record = list.get(0);
            Map<Object, Object> value = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
            // 3.创建订单
            createVoucherOrder(voucherOrder);
            // 4.确认消息 XACK
            stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
        } catch (Exception e) {
            log.error("处理订单异常", e);
        }
    }
}
}
    @Override
    public Result seckillVoucher(Long voucherId){
        //获取用户
        Long userId = UserHolder.getUser().getId();

        //获取订单
        long orderId = redisIdWorker.nextId("order");

        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        int r = result != null ? result.intValue() : -1;//r = [0,1,2]

        if ( r != 0){

            //r不为0 代表没有购买资格
            return Result.fail("下单失败,原因:"+ (r == 1 ? "库存不足":"不能重复下单"));
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        String orderMessage = JSONUtil.toJsonStr(voucherOrder);

        rabbitTemplate.setConfirmCallback((correlationData, b, s) -> {

            if (!b){
                MessageProperties messageProperties = correlationData.getReturnedMessage().getMessageProperties();
                log.debug("message confirm fail cause by："+s);
                log.debug("message:："+messageProperties.getMessageId());
                log.debug("exchangeName："+messageProperties.getReceivedExchange());
                log.debug("routingKey："+messageProperties.getReceivedRoutingKey());
                log.debug("重新发送");
                String exchangeName = messageProperties.getReceivedExchange();
                String routingKey = messageProperties.getReceivedRoutingKey();
                String message = new String(correlationData.getReturnedMessage().getBody());
                rabbitTemplate.convertAndSend(exchangeName,routingKey,message);
            }
        });
        //向rabbitmq发送消息
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME,"seckill.vouncher",orderMessage);

        //返回订单id
        return Result.ok(orderId);


    }

    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//
//        Long userId = UserHolder.getUser().getId();
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//
//        if (count > 0) {
//            //如果该用户已经抢到了此代金券
//            return Result.fail("用户已经购买过一次了");
//        }
//        //8.  减少库存
//
//        boolean result = iSeckillVoucherService.update()
//                .setSql("stock = stock -1")
//                .eq("voucher_Id", voucherId).gt("stock", 0)
//                .update();
//        if (result == false) {
//
//            //  失败 返回错误信息
//            return Result.fail("优惠券已经被抢完了，请等待下次秒杀活动");
//
//        }
//        //9.  生成订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//
//        Long orderId = redisIdWorker.nextId("order");
//        //9.1 订单id
//        voucherOrder.setId(orderId);
//
//
//        voucherOrder.setUserId(userId);
//        //9.2 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //10.  保存订单
//        save(voucherOrder);
//
//        return Result.ok(voucherOrder);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.  根据优惠券id查询优惠券信息
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//
//        //2.  判断秒杀活动是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//
//            //3.  未开始 返回错误信息
//            return Result.fail("秒杀活动暂未开启");
//        }
//
//        //4.  判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//
//            //3.  已经结束 返回错误信息
//            return Result.fail("秒杀活动已经结束");
//        }
//
//        //4.  在活动时间范围内 抢秒杀优惠券
//
//        //5.  判断库存
//        if (voucher.getStock() < 1) {
//
//            //6.  不足 返回错误信息
//            return Result.fail("优惠券已经被抢完了，请等待下次秒杀活动");
//        }
//
//        //7  用户id 判断一人一单
//
//
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//
//        //尝试获取锁
//        boolean isLock = simpleRedisLock.tryLock(500L);
//        if (!isLock) {
//            //获取锁失败 返回错误信息
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            //获得代理对象(处理事务)
//            VoucherOrderServiceImpl voucherOrderServiceProxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
//            return voucherOrderServiceProxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            simpleRedisLock.unLock();
//        }
//
//
//    }
}
