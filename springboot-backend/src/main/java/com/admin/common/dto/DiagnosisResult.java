package com.admin.common.dto;

import lombok.Data;

@Data
public class DiagnosisResult {
    private Long nodeId;
    private String nodeName;
    private String targetIp;
    private Integer targetPort;
    private String description;
    private boolean success;
    private String message;
    private double averageTime;
    private double packetLoss;
    private long timestamp;
    private Integer fromChainType;
    private Integer fromInx;
    private Integer toChainType;
    private Integer toInx;
}
