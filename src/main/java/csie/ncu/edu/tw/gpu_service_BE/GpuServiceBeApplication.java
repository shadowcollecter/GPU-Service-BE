package csie.ncu.edu.tw.gpu_service_BE;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableScheduling
@RestController
public class GpuServiceBeApplication {

    public static void main(String[] args) {
      SpringApplication.run(GpuServiceBeApplication.class, args);
    }

    @Bean
    public ServletWebServerFactory servletWebServerFactory() {
        return new TomcatServletWebServerFactory();
    }

    @GetMapping("/hello")
    public String hello(@RequestParam(value = "name", defaultValue = "World") String name) {
      return String.format("Hello %s!", name);
    }

    @GetMapping("/greet")
    public String greet(@RequestParam(value = "name", defaultValue = "Guest") String name) {
      return String.format("Greetings, %s!", name);
    }

	@GetMapping("/admin")
	public String getMethodName(@RequestParam String param) {
		return new String("I am secret");
	}
}
