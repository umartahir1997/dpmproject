package ca.mcgill.ecse211.Navigation;

import ca.mcgill.ecse211.Ev3Boot.Ev3Boot;
import ca.mcgill.ecse211.Ev3Boot.MotorController;
import ca.mcgill.ecse211.Localization.Localizer;
import ca.mcgill.ecse211.odometer.Odometer;
import ca.mcgill.ecse211.odometer.OdometerExceptions;
import lejos.hardware.Sound;
//import ca.mcgill.ecse211.odometer.Odometer;
//import ca.mcgill.ecse211.odometer.OdometerExceptions;
import lejos.hardware.motor.EV3LargeRegulatedMotor;
import lejos.robotics.SampleProvider;

/**
 * This class contains methods to allow the robot to navigate to specified
 * waypoint, or to turn to specific angle.
 * 
 * It is mainly called by the Ev3Boot class to navigate to strategic point. It
 * is also called by other LightLocalization class to navigate to a line
 * intersection.
 * 
 * @author Imad Dodin
 * @author An Khang Chau
 * @author Chaimae Fahmi
 *
 */
public class Navigator extends MotorController{

	private static final int BLACK = 300;
	private static final int errorMargin = 150;


	private static final double TILE_SIZE = Ev3Boot.getTileSize();
	private static final int TURN_ERROR = 1;
	private static SampleProvider colorSensorLeft = Ev3Boot.getColorLeft();
	private static SampleProvider colorSensorRight = Ev3Boot.getColorRight();
	private static float[] colorLeftBuffer = Ev3Boot.getColorLeftBuffer();
	private static float[] colorRightBuffer = Ev3Boot.getColorRightBuffer();

	private static float colorLeft;
	private static float colorRight;
	private static float oldColorLeft;
	private static float oldColorRight;
	private static final long CORRECTION_PERIOD = 10;
	static long correctionStart;
	static long correctionEnd;

	private static Odometer odo;
	private static double[] currentPosition;
	private static double[] lastPosition;
	private static boolean isNavigating;
	private static SampleProvider usDistance = Ev3Boot.getUSDistance();
	private static float[] usData = Ev3Boot.getUSData();

	private static double ReOrientDistance;
	private static double distanceDifference;
	private static double distance;

	private static boolean updatePosition;
	private static double deltaX;
	private static double deltaY;

	private static double baseAngle;
	private static double adjustAngle;

	private static double newX;
	private static double newY;
	private static double newTheta;

	private static double phi;

	/**
	 * 
	 * This method execute navigation to a specific point, if wanted it can light
	 * localize upon arrival.
	 * 
	 * This method causes the robot to travel to an intermediate waypoint in the
	 * case of diagonal travel. The method continuously polls the 2 rear light
	 * sensors. In the case that a line is detected by one of them, its respective
	 * motor is stopped until the robot corrects its heading (the second light
	 * sensor detects a line). In a loop calculate distance between current position
	 * and arrival point, if the distance is smaller than treshHold, stop the
	 * motors. When the distance from arrival point is acceptable stop the motors
	 * and light localize if specified.
	 * 
	 * @param x: x coordinate to navigate to
	 * @param y: y coordinate to navigate to
	 * @param treshHold: acceptable error distance
	 * @param localizing: should to robot localize upon arrival
	 */
	public static void travelTo(double x, double y, int treshHold, boolean checkLine) {
		try {
			odo = Odometer.getOdometer();
		} catch (OdometerExceptions e) {
			e.printStackTrace();
			return;
		}

		currentPosition = odo.getXYT();
		lastPosition = odo.getXYT();

		for (EV3LargeRegulatedMotor motor : new EV3LargeRegulatedMotor[] { leftMotor, rightMotor }) {
			motor.stop();
			motor.setAcceleration(500);
		}

		isNavigating = true;

		deltaX = x * TILE_SIZE - currentPosition[0];
		deltaY = y * TILE_SIZE - currentPosition[1];

		if (deltaX == 0 && deltaY != 0) {
			turnTo(deltaY < 0 ? 180 : 0);
		} else {
			baseAngle = deltaX > 0 ? 90 : 270;
			if ((deltaY > 0 && deltaX > 0) || (deltaY < 0 && deltaX < 0)) {
				adjustAngle = -1 * Math.toDegrees(Math.atan(deltaY / deltaX));
			} else {
				adjustAngle = Math.toDegrees(Math.atan(Math.abs(deltaY) / Math.abs(deltaX)));
			}

			// OdometerCorrection.isCorrecting = false;
			turnTo(baseAngle + adjustAngle);
			// OdometerCorrection.isCorrecting = true;

		}

		setSpeeds(FORWARD_SPEED);
		bothForwards();

		while (isNavigating) {

//			System.out.println("Distance:"+distance);
//			System.out.println("delta x: "+ deltaX);
//			System.out.println("delta y: "+ deltaY);

			correctionStart = System.currentTimeMillis();

			if (checkLine)
				checkForLines();

			currentPosition = odo.getXYT();

			deltaX = x * TILE_SIZE - currentPosition[0];
			deltaY = y * TILE_SIZE - currentPosition[1];
			distance = Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2));

			if (distance < treshHold) {
				leftMotor.stop(true);
				rightMotor.stop();
				isNavigating = false;
				if (treshHold > 4) {
					travelTo(x, y, 2, false);
				}
				Sound.beepSequence();

				return;
			}

			correctionEnd = System.currentTimeMillis();
			if (correctionEnd - correctionStart < CORRECTION_PERIOD) {
				try {
					Thread.sleep(CORRECTION_PERIOD - (correctionEnd - correctionStart));
				} catch (InterruptedException e) {

				}
			}

		}

	}

	public static void checkForLines() {
		oldColorLeft = colorLeft;
		oldColorRight = colorRight;

		colorSensorLeft.fetchSample(colorLeftBuffer, 0);
		colorLeft = colorLeftBuffer[0] * 1000;

		colorSensorRight.fetchSample(colorRightBuffer, 0);
		colorRight = colorRightBuffer[0] * 1000;

		if (colorLeft - oldColorLeft > 19) {
			if (colorRight - oldColorRight > 19 || !pollMultiple(false)) {
				return;
			}

			leftMotor.stop(true);
			double theta = odo.getXYT()[2];
			while (colorRight - oldColorRight <= 19) {
				updatePosition = true;
				if (rightMotor.getSpeed() != CORRECTOR_SPEED) {
					rightMotor.stop();
					setSpeeds(CORRECTOR_SPEED);
					rightMotor.forward();
				}
				phi = Math.abs(odo.getXYT()[2] - theta) % 360;
				phi = phi > 180 ? 360 - phi : phi;
				if (phi > 15) {
					rightMotor.stop(true);
					leftMotor.stop();
					turnBy(15, true, true);
					forwardBy(-5);
					bothForwards();
					updatePosition = false;
					break;
				}
				oldColorRight = colorRight;
				colorSensorRight.fetchSample(colorRightBuffer, 0);
				colorRight = colorRightBuffer[0] * 1000;
				// System.out.println(colorRight-oldColorRight);
			}

			Sound.beep();
			rightMotor.stop();

			currentPosition = odo.getXYT();

			// How much do you increment by?
			int yInc = Math.round((float) Math.cos(Math.toRadians(currentPosition[2])));
			int xInc = Math.round((float) Math.sin(Math.toRadians(currentPosition[2])));

			if (updatePosition) {
//				yCount += yInc;
//				xCount += xInc;
//
//				if (xInc < 0) {
//					newX = xCount * TILE_SIZE - Localizer.SENSOR_OFFSET;
//					newTheta = 270;
//				} else if (xInc > 0) {
//					newX = (xCount) * TILE_SIZE + Localizer.SENSOR_OFFSET;
//					newTheta = 90;
//				} else {
//					newX = currentPosition[0];
//				}
//
//				if (yInc < 0) {
//					newY = yCount * TILE_SIZE - Localizer.SENSOR_OFFSET;
//					newTheta = 180;
//				} else if (yInc > 0) {
//					newY = (yCount) * TILE_SIZE + Localizer.SENSOR_OFFSET;
//					newTheta = 0;
//				} else {
//					newY = currentPosition[1];
//				}
				
				if (xInc < 0) {
					newX = roundToNearestTileSize(currentPosition[0] + Localizer.SENSOR_OFFSET) - Localizer.SENSOR_OFFSET;
					newTheta = 270;
				} else if (xInc > 0) {
					newX = roundToNearestTileSize(currentPosition[0] - Localizer.SENSOR_OFFSET) + Localizer.SENSOR_OFFSET;
					newTheta = 90;
				} else {
					newX = currentPosition[0];
				}

				if (yInc < 0) {
					newY = roundToNearestTileSize(currentPosition[1] + Localizer.SENSOR_OFFSET) - Localizer.SENSOR_OFFSET;
					newTheta = 180;
				} else if (yInc > 0) {
					newY = roundToNearestTileSize(currentPosition[1] - Localizer.SENSOR_OFFSET) + Localizer.SENSOR_OFFSET;
					newTheta = 0;
				} else {
					newY = currentPosition[1];
				}
				
				odo.setXYT(newX, newY, newTheta);
//				System.out.println("New co-ordinates: " + newX + " , " + newY);
			}

//			System.out.println("X COUNT INCREASED!: " + xCount);
//			System.out.println("Y COUNT INCREASED!: " + yCount);

			// Are we crossing tile boundary from the upper or lower boundary?

			setSpeeds(FORWARD_SPEED);
			bothForwards();

			try {
				Thread.sleep(400);
				colorSensorRight.fetchSample(colorRightBuffer, 0);
				colorRight = colorRightBuffer[0] * 1000;
				colorSensorLeft.fetchSample(colorLeftBuffer, 0);
				colorLeft = colorLeftBuffer[0] * 1000;
				oldColorLeft = colorLeft;
				oldColorRight = colorRight;

			} catch (InterruptedException e) {
			}

		} else if (colorRight - oldColorRight > 19) {
			if (colorLeft - oldColorLeft > 19 || !pollMultiple(true)) {
				return;
			}

			// System.out.println("Right line detected:\n" + (colorRight - oldColorRight));

			rightMotor.stop(true);
			double theta = odo.getXYT()[2];
			while (colorLeft - oldColorLeft <= 19) {
				updatePosition = true;
				if (leftMotor.getSpeed() != CORRECTOR_SPEED) {
					leftMotor.stop();
					setSpeeds(CORRECTOR_SPEED);
					leftMotor.forward();
				}
				phi = Math.abs(odo.getXYT()[2] - theta) % 360;
				phi = phi > 180 ? 360 - phi : phi;
				if (phi > 15) {
					rightMotor.stop(true);
					leftMotor.stop();
					turnBy(15, false, true);
					forwardBy(-5);
					bothForwards();
					updatePosition = false;
					break;
				}
				oldColorLeft = colorLeft;
				colorSensorLeft.fetchSample(colorLeftBuffer, 0);
				colorLeft = colorLeftBuffer[0] * 1000;
				// System.out.println(colorLeft-oldColorLeft);
			}

			Sound.beep();
			leftMotor.stop();

			currentPosition = odo.getXYT();

			// How much do you increment by?
			int yInc = Math.round((float) Math.cos(Math.toRadians(currentPosition[2])));
			int xInc = Math.round((float) Math.sin(Math.toRadians(currentPosition[2])));

			if (updatePosition) {
//				yCount += yInc;
//				xCount += xInc;

				if (xInc < 0) {
					newX = roundToNearestTileSize(currentPosition[0] + Localizer.SENSOR_OFFSET) - Localizer.SENSOR_OFFSET;
					newTheta = 270;
				} else if (xInc > 0) {
					newX = roundToNearestTileSize(currentPosition[0] - Localizer.SENSOR_OFFSET) + Localizer.SENSOR_OFFSET;
					newTheta = 90;
				} else {
					newX = currentPosition[0];
				}

				if (yInc < 0) {
					newY = roundToNearestTileSize(currentPosition[1] + Localizer.SENSOR_OFFSET) - Localizer.SENSOR_OFFSET;
					newTheta = 180;
				} else if (yInc > 0) {
					newY = roundToNearestTileSize(currentPosition[1] - Localizer.SENSOR_OFFSET) + Localizer.SENSOR_OFFSET;
					newTheta = 0;
				} else {
					newY = currentPosition[1];
				}

				odo.setXYT(newX, newY, newTheta);
//				System.out.println("New co-ordinates: " + newX + " , " + newY);
			}

			setSpeeds(FORWARD_SPEED);
			bothForwards();

			try {
				Thread.sleep(400);
				colorSensorRight.fetchSample(colorRightBuffer, 0);
				colorRight = colorRightBuffer[0] * 1000;
				colorSensorLeft.fetchSample(colorLeftBuffer, 0);
				colorLeft = colorLeftBuffer[0] * 1000;
				oldColorLeft = colorLeft;
				oldColorRight = colorRight;
			} catch (InterruptedException e) {
			}
		}
	}

	public static void toStraightNavigator(double x, double y, int threshold) {
		//System.out.println("coord: (" + x + ", " + y + ")");
		try {
			odo = Odometer.getOdometer();
		} catch (OdometerExceptions e) {
			e.printStackTrace();
			return;
		}
		currentPosition = odo.getXYT();
		travelTo(currentPosition[0] / TILE_SIZE, y, threshold, true);
		travelTo(x, y, threshold, true);
	}

	

	/**
	 * Turn by the specified angle theta, can be both positive or negative
	 * 
	 * @param clockwise: true to turn clockwise and false to turn counter clockwise.
	 * @param theta: amount of degree the robot has to turn.
	 */
	public static void turnBy(double theta, boolean clockwise, boolean blocking) {

		setSpeeds(TURN_SPEED + 50);
		if (clockwise == false) {
			leftMotor.rotate(-convertAngle(Ev3Boot.getWheelRad(), Ev3Boot.getTrack(), theta), true);
			rightMotor.rotate(convertAngle(Ev3Boot.getWheelRad(), Ev3Boot.getTrack(), theta), !blocking);
		} else {
			leftMotor.rotate(convertAngle(Ev3Boot.getWheelRad(), Ev3Boot.getTrack(), theta), true);
			rightMotor.rotate(-convertAngle(Ev3Boot.getWheelRad(), Ev3Boot.getTrack(), theta), !blocking);
		}

	}


	public static boolean pollMultiple(Boolean isRight) {
		int sampleCount = 10;
		float sum = 0;
		SampleProvider sample = isRight ? colorSensorRight : colorSensorLeft;
		float[] buffer = isRight ? colorRightBuffer : colorLeftBuffer;

		for (int i = 0; i < sampleCount; i++) {
			sample.fetchSample(buffer, 0);
			sum += buffer[0] * 1000;
		}

		float avg = sum / sampleCount;
		if (avg > BLACK - errorMargin && avg < BLACK + errorMargin) {
			return true;
		} else {
			return false;
		}

	}
	
	public static double roundToNearestTileSize(double value)
	{
		return TILE_SIZE * (Math.round(value/TILE_SIZE));
	}

}