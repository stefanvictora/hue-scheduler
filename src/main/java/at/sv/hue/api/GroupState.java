package at.sv.hue.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class GroupState {
	private final Integer bri;
	private final Integer ct;
	private final Double x;
	private final Double y;
	private final Integer hue;
	private final Integer sat;
	private final String effect;
	private final String colormode;
	private final boolean on;
	private final boolean allOn;
	private final boolean anyOn;
}
