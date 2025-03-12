package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private IVoucherOrderService proxy;     //动态代理对象
    //构建lua脚本--判断是否具有下单资格并发送订单消息
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("luas/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //线程池
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //创建类实例后立即执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.order";
        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2. 判断消息是否获取成功
                    if(list==null || list.isEmpty()){
                        //2.1 失败：再次尝试
                        continue;
                    }
                    //2.2 成功：创建订单，解析消息
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(order);
                    //3. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",entries.getId());
                } catch (Exception e) {
                    log.info("处理订单异常：{}", e.getMessage());
                    handlePendingList();
                }
            }
        }
    }

    //处理消息队列中订单
    private void handleVoucherOrder(VoucherOrder order) {
        //1. 获取用户
        Long userid = order.getUserId();
        //2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userid);
        boolean locked = lock.tryLock();
        if (!locked) {
            log.error("下单失败");
            return;
        }
        //3. 调用动态代理执行创建订单事务
        proxy.createVoucherOrder(order);
        lock.unlock();
    }
    //处理pendingList中订单
    private void handlePendingList() {
        String queueName = "stream.order";
        while(true){
            try {
                //1. 获取pendingList中的异常订单信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //2. 判断消息是否获取成功
                if(list==null || list.isEmpty()){
                    //2.1 失败：再次尝试
                    break;
                }
                //2.2 成功：创建订单，解析消息
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> values = entries.getValue();
                VoucherOrder order = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(order);
                //3. ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1",entries.getId());
            } catch (Exception e) {
                log.info("处理订单异常：{}", e.getMessage());
            }
        }
    }


    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本判断是否具有下单资格并发送消息
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), //这里是key数组，没有key，就传的一个空集合
                voucherId.toString(), userId.toString(), orderId.toString()
        );
        int r = result.intValue();
        //2. 无下单资格：返回错误信息
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
        }
        //3. 创建动态代理（完成事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok();
    }


    /**
     * 创建订单
     * @param order
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        //1. 判断用户是否已购买
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买！");
            return;
        }
        //2. 扣除库存+乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        //3. 创建订单
        save(order);
    }

    /*优化前：
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1. 根据id查询voucher信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2. 判断秒杀时间
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //3. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }
        Long userId = UserHolder.getUser().getId();

        //4. 分布式锁——防止并发异常
        //RedisLock redisLock = new RedisLock("order:" + userId, stringRedisTemplate);
        //使用redisson，实现可重现锁
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean islock = lock.tryLock();    //参数中可以设置最长等待时间和锁的释放时间以及时间单位
        if (!islock){
            return Result.fail("不允许重复下单，下单失败！");
        }
        try{
            //动态代理
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //4. 判断用户是否已购买
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已购买");
        }
        //6. 扣除库存+乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        //7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(redisIdWorker.nextId("order"));
        save(voucherOrder);
        //8. 返回id
        return Result.ok(voucherOrder.getId());
    }
    */

    /*基于阻塞队列实现创建订单
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //类实例构造后立马执行init方法
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取阻塞队列中的订单信息
                    VoucherOrder order = orderTasks.take();
                    //2. 创建订单
                    handlerVoucherOrder(order);
                } catch (InterruptedException e) {
                    log.info("处理订单异常：{}", e.getMessage());
                }
            }
        }
    }
    */

}
