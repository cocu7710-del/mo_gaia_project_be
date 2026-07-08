package com.gaiaproject.mo_gaia_project_be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 리치 응답 타이머 스위퍼 (LeechTimeoutJob)
public class MoGaiaProjectBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoGaiaProjectBeApplication.class, args);
    }

}
