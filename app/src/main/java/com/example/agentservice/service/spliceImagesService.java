package com.example.agentservice.service;

import com.example.agentservice.imm.request.SpliceImagesRequest;

public interface spliceImagesService {

    String spliceImagesHorizontally(SpliceImagesRequest request) throws Exception;

    String spliceImagesVertically(SpliceImagesRequest request) throws Exception;
}
