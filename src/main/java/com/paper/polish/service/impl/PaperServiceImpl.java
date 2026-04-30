package com.paper.polish.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paper.polish.entity.Paper;
import com.paper.polish.mapper.PaperMapper;
import com.paper.polish.service.PaperService;
import org.springframework.stereotype.Service;

@Service
public class PaperServiceImpl extends ServiceImpl<PaperMapper, Paper> implements PaperService {
}
