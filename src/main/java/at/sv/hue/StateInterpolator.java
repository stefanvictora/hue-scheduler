package at.sv.hue;

import at.sv.hue.api.PutCall;
import at.sv.hue.color.ColorModeConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;

public final class StateInterpolator {
	
	private final ScheduledState state;
	private final ScheduledState previousState;
	private final ZonedDateTime now;
	
	public StateInterpolator(ScheduledState state, ScheduledState previousState, ZonedDateTime now) {
		this.state = state;
		this.previousState = previousState;
		this.now = now;
	}
	
	public PutCall getInterpolatedPutCall() {
		if (state.getTransitionTimeBefore() == null) {
			return null; // no interpolation needed
		}
		int timeUntilThisState = state.getAdjustedTransitionTimeBefore(now);
		if (timeUntilThisState == 0) {
			return null; // the state is already reached
		}
		if (timeUntilThisState == state.getTransitionTimeBefore()) {
			return previousState.getPutCall(now); // directly at start, just return previous put call
		}
		return interpolate();
	}
	
	/**
	 * P = P0 + t(P1 - P0)
	 */
	private PutCall interpolate() {
		BigDecimal interpolatedTime = state.getInterpolatedTime(now);
		PutCall previous = previousState.getPutCall(now);
		PutCall target = state.getPutCall(now);
		
		convertColorModeIfNeeded(previous);
		
		previous.setBri(interpolateInteger(interpolatedTime, target.getBri(), previous.getBri()));
		previous.setCt(interpolateInteger(interpolatedTime, target.getCt(), previous.getCt()));
		previous.setHue(interpolateHue(interpolatedTime, target.getHue(), previous.getHue()));
		previous.setSat(interpolateInteger(interpolatedTime, target.getSat(), previous.getSat()));
		previous.setX(interpolateDouble(interpolatedTime, target.getX(), previous.getX()));
		previous.setY(interpolateDouble(interpolatedTime, target.getY(), previous.getY()));
		return previous;
	}
	
	private void convertColorModeIfNeeded(PutCall previousPutCall) {
		Double[][] colorGamut = previousState.getCapabilities().getColorGamut();
		ColorModeConverter.convertIfNeeded(previousPutCall, colorGamut, previousState.getColorMode(), state.getColorMode());
	}
	
	private static Integer interpolateInteger(BigDecimal interpolatedTime, Integer target, Integer previous) {
		if (target == null) {
			return previous;
		}
		if (previous == null) {
			return null;
		}
		BigDecimal diff = BigDecimal.valueOf(target - previous);
		return BigDecimal.valueOf(previous)
				.add(interpolatedTime.multiply(diff))
				.setScale(0, RoundingMode.HALF_UP)
				.intValue();
	}
	
	private static Double interpolateDouble(BigDecimal interpolatedTime, Double target, Double previous) {
		if (target == null) {
			return previous;
		}
		if (previous == null) {
			return null;
		}
		BigDecimal diff = BigDecimal.valueOf(target - previous);
		return BigDecimal.valueOf(previous)
				.add(interpolatedTime.multiply(diff))
				.setScale(5, RoundingMode.HALF_UP)
				.doubleValue();
	}
	
	/**
	 * Perform special interpolation for hue values, as they wrap around i.e. 0 and 65535 are both considered red.
	 * This means that we need to decide in which direction we want to interpolate, to get the smoothest transition.
	 */
	private static Integer interpolateHue(BigDecimal interpolatedTime, Integer target, Integer previous) {
		if (target == null) {
			return previous;
		}
		if (previous == null) {
			return null;
		}
		if (previous == 0 && target == ScheduledState.MAX_HUE_VALUE || previous == ScheduledState.MAX_HUE_VALUE && target == 0) {
			return 0;
		}
		int diff = ((target - previous + ScheduledState.MIDDLE_HUE_VALUE) % (ScheduledState.MAX_HUE_VALUE + 1)) - ScheduledState.MIDDLE_HUE_VALUE;
		return BigDecimal.valueOf(previous)
				.add(interpolatedTime.multiply(BigDecimal.valueOf(diff)))
				.setScale(0, RoundingMode.HALF_UP)
				.intValue();
	}
}
