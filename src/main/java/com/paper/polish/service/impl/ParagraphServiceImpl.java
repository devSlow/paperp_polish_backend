package com.paper.polish.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paper.polish.entity.Paragraph;
import com.paper.polish.mapper.ParagraphMapper;
import com.paper.polish.service.ParagraphService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ParagraphServiceImpl extends ServiceImpl<ParagraphMapper, Paragraph> implements ParagraphService {

    @Override
    public List<Paragraph> listByPaperId(String paperId) {
        return list(new LambdaQueryWrapper<Paragraph>()
                .eq(Paragraph::getPaperId, paperId)
                .orderByAsc(Paragraph::getParagraphIndex));
    }
}
