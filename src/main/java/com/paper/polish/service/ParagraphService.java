package com.paper.polish.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.paper.polish.entity.Paragraph;

import java.util.List;

public interface ParagraphService extends IService<Paragraph> {

    List<Paragraph> listByPaperId(String paperId);
}
