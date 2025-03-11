package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherService extends IService<Voucher> {

    /**
     * 查询优惠券信息
     * @param shopId
     * @return
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 新增秒杀券
     * @param voucher
     */
    void addSeckillVoucher(Voucher voucher);
}
