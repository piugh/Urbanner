-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]
--1.3 订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1 库存key   key 是优惠的业务名称加优惠券id  value 是优惠券的库存数
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key   key 也是拼接的业务名称加优惠权id  而value是用户id， 这是一个set集合，凡购买该优惠券的用户都会将其id存入集合中
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0)  then  --将get的value先转为数字类型才能判断比较
    -- 3.2 库存不足，返回1
    return 1
end
-- 3.3 判断用户是否下单 sismember orderKey userId命令，判断当前key集合中，是否存在该value；返回1存在，0不存在
if (redis.call('sismember', orderKey, userId) == 1) then
    --3.4 存在说明是重复下单，返回2
    return 2
end
-- 3.5 扣库存
redis.call('incrby', stockKey, -1)
-- 3.6 下单（保存用户）
redis.call('sadd', orderKey, userId)
--3.7 发送消息到队列中
redis.call('XADD', 'stream.order','*','userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
