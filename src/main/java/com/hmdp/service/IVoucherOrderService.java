package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 新建秒杀订单
     * @param order
     */
    void createVoucherOrder(VoucherOrder order);
}
