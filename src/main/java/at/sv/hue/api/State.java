package at.sv.hue.api;

import at.sv.hue.ColorMode;

public interface State {
	Integer getBrightness();
	
	Integer getColorTemperature();
	
	Double getX();
	
	Double getY();
	
	Integer getHue();
	
	Integer getSat();
	
	String getEffect();
	
	boolean isOn();
	
	ColorMode getColormode();
	
	default boolean isColorLoopEffect() {
		return "colorloop".equals(getEffect());
	}
}
