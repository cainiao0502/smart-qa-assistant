package com.nailinai.ragent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.nailinai.ragent",
        "com.nailinai.ragent.framework",
        "com.nailinai.ragent.infra"
})
@MapperScan({
        "com.nailinai.ragent.mapper",
        "com.nailinai.ragent.user.mapper"
})
public class RagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }
}