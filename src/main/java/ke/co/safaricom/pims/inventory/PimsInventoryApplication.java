package ke.co.safaricom.pims.inventory;

import ke.co.safaricom.pims.inventory.config.ErpNextProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ErpNextProperties.class})
public class PimsInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(PimsInventoryApplication.class, args);
    }
}
