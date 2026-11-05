package app.kitappla.api.dto;

public record FeaturesDto(
        boolean shipping,
        boolean purchase,
        boolean address,
        boolean handover,
        boolean document,
        boolean messaging,
        boolean reports
) {
    public FeaturesDto(boolean shipping, boolean purchase, boolean address, boolean messaging, boolean reports) {
        this(shipping, purchase, address, !shipping, false, messaging, reports);
    }
}
