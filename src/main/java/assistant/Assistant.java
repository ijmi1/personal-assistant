package assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class Assistant {
    public static void main(String[] args) {
        SpringApplication.run(Assistant.class, args);
    }
}
