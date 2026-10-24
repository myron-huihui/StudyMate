package com.studymate.ai.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryTurn {
    private String role;
    private String content;
    private String model;
    private LocalDateTime createdAt;
}
