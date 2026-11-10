package com.admin.common.dto;


import lombok.Data;

import java.util.List;

@Data
public class ChainNodesItems {

    private String mode;

    private List<Integer> nodeIds;

}
