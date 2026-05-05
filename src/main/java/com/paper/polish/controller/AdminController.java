package com.paper.polish.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.paper.polish.common.Result;
import com.paper.polish.entity.RedeemCode;
import com.paper.polish.service.RedeemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RedeemService redeemService;

    @PostMapping("/redeem/generate")
    public Result<List<RedeemCode>> generate(@RequestBody Map<String, Integer> body) {
        Integer count = body.get("count");
        Integer amount = body.get("amount");
        Integer remain = body.get("remain");
        if (count == null || amount == null || remain == null) {
            return Result.fail(400, "参数不完整，需要 count、amount 和 remain");
        }
        if (count < 1 || count > 1000) {
            return Result.fail(400, "count 范围：1-1000");
        }
        if (amount < 1 || amount > 1000) {
            return Result.fail(400, "amount 范围：1-1000");
        }
        if (remain < 1 || remain > 10000) {
            return Result.fail(400, "remain 范围：1-10000");
        }
        List<RedeemCode> codes = redeemService.generateCodes(count, amount, remain);
        return Result.ok(codes);
    }

    @GetMapping("/redeem/list")
    public Result<IPage<RedeemCode>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer used) {
        Page<RedeemCode> p = new Page<>(page, size);
        LambdaQueryWrapper<RedeemCode> wrapper = new LambdaQueryWrapper<RedeemCode>()
                .orderByDesc(RedeemCode::getCreatedAt);
        if (used != null) {
            wrapper.eq(RedeemCode::getUsed, used);
        }
        return Result.ok(redeemService.page(p, wrapper));
    }

    @DeleteMapping("/redeem/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        redeemService.removeById(id);
        return Result.ok();
    }
}
