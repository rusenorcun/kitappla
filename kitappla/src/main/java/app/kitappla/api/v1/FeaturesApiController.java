package app.kitappla.api.v1;

import app.kitappla.api.dto.FeaturesDto;
import app.kitappla.config.Features;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/features")
public class FeaturesApiController {

    private final Features features;

    public FeaturesApiController(Features features) {
        this.features = features;
    }

    @GetMapping
    public ResponseEntity<FeaturesDto> getFeatures() {
        return ResponseEntity.ok(new FeaturesDto(
                features.isShipping(),
                features.isPurchase(),
                features.isAddress(),
                features.isHandover(),
                features.isDocument(),
                true,
                true
        ));
    }
}
