package com.paper.polish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paper.polish.entity.DailyUsage;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;

public interface DailyUsageMapper extends BaseMapper<DailyUsage> {
    DailyUsage selectByDeviceAndDate(@Param("deviceId") String deviceId, @Param("date") LocalDate date);
}