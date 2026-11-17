package com.liubai.diary;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.liubai.diary")
@EnableScheduling
public class DiaryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiaryApplication.class, args);
    }
}
