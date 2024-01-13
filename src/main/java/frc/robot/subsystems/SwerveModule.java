// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.StatusFrameEnhanced;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonFX;
import com.ctre.phoenix.sensors.AbsoluteSensorRange;
import com.ctre.phoenix.sensors.WPI_CANCoder;
import com.ctre.phoenix6.hardware.CANcoder;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import frc.robot.Constants;
import frc.robot.TrapezoidalConstraint;
import frc.robot.Constants.DriveConstants;
import frc.robot.Constants.SwerveModuleConstants;

import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMaxLowLevel;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.SparkMaxPIDController;

public class SwerveModule {
  private final CANSparkMax m_driveMotor;
  private final CANSparkMax m_turningMotor;
  private final SparkMaxPIDController m_pidController;
  private double m_kP;
  private double m_kI;
  private double m_kD;
  private double m_idealVelocity = 0;

  private final RelativeEncoder m_driveEncoder;
  private final CANcoder m_turningEncoder;

  // Using a TrapezoidProfile PIDController to allow for smooth turning
  private final ProfiledPIDController m_turningPIDController =
    new ProfiledPIDController(
      SwerveModuleConstants.kPTurningController,
      SwerveModuleConstants.kITurningController,
      SwerveModuleConstants.kDTurningController,
      new TrapezoidProfile.Constraints(
        SwerveModuleConstants.kMaxAngularSpeedRadiansPerSecond,
        SwerveModuleConstants.kMaxAngularAccelerationRadiansPerSecondSquared));
  
  private final Rotation2d m_encoderOffset;

  /**
   * Constructs a SwerveModule.
   *
   * @param driveMotorChannel The channel of the drive motor.
   * @param turningMotorChannel The channel of the turning motor.
   * @param driveEncoderChannels The channels of the drive encoder.
   * @param turningEncoderChannels The channels of the turning encoder.
   * @param driveMotorReversed Whether the drive encoder is reversed.
   * @param turningEncoderReversed Whether the turning encoder is reversed.
   */
  public SwerveModule(
      int driveMotorChannel,
      int turningMotorChannel,
      int turningEncoderChannel,
      boolean driveMotorReversed,
      boolean turningMotorReversed,
      boolean turningEncoderReversed,
      Rotation2d encoderOffset) {
    
    m_kP = SwerveModuleConstants.kPDriveController;
    m_kI = SwerveModuleConstants.kIDriveController;
    m_kD = SwerveModuleConstants.kDDriveController;
    
    m_driveMotor = new CANSparkMax(driveMotorChannel, MotorType.kBrushless);
    m_driveMotor.restoreFactoryDefaults();
    m_driveMotor.setInverted(driveMotorReversed);

    m_driveEncoder = m_driveMotor.getEncoder();

    m_pidController = m_driveMotor.getPIDController();
    m_pidController.setFeedbackDevice(m_driveEncoder);
    // m_driveMotor.setStatusFramePeriod(StatusFrameEnhanced.Status_13_Base_PIDF0, 10, 10);
    m_pidController.setP(m_kP, 0);
    m_pidController.setI(m_kI, 0);
    m_pidController.setD(m_kD, 0);
    m_pidController.setFF(0);
    
    m_driveMotor.setSmartCurrentLimit(DriveConstants.kSmartCurrentLimit);

    m_turningMotor = new CANSparkMax(turningMotorChannel, MotorType.kBrushless);
    m_turningMotor.setInverted(turningMotorReversed);
    m_turningMotor.setSmartCurrentLimit(DriveConstants.kSmartCurrentLimit);

    m_turningEncoder = new CANcoder(turningEncoderChannel);
    // m_turningEncoder.configSensorDirection(turningEncoderReversed, 10);
    // m_turningEncoder.configAbsoluteSensorRange(AbsoluteSensorRange.Unsigned_0_to_360);

    // Limit the PID Controller's input range between -pi and pi and set the input
    // to be continuous.
    m_turningPIDController.enableContinuousInput(-Math.PI, Math.PI);
    m_encoderOffset = encoderOffset;
  }
  /**
   * Returns the current state of the module.
   *
   * @return The current state of the module.
   */
  public SwerveModuleState getState() {
    return new SwerveModuleState(
      m_driveMotor.getSelectedSensorVelocity()*SwerveModuleConstants.kDriveEncoderDistancePerPulse*10.0,
      getPosR2d()
    );
  }

  public SwerveModulePosition getPosition() {
    return new SwerveModulePosition(
      m_driveMotor.getSelectedSensorPosition()*SwerveModuleConstants.kDriveEncoderDistancePerPulse,
      getPosR2d()
    );
  }

  /**
   * Sets the desired state for the module.
   *
   * @param desiredState Desired state with speed and angle.
   */
  public void setDesiredState(SwerveModuleState desiredState) {
    // Optimize the reference state to avoid spinning further than 90 degrees
    SwerveModuleState state =
      SwerveModuleState.optimize(desiredState, getPosR2d());

    final double driveVelocityDesired = state.speedMetersPerSecond
      / (SwerveModuleConstants.kDriveEncoderDistancePerPulse);
    
    TrapezoidalConstraint profile = new TrapezoidalConstraint(
      SwerveModuleConstants.kMaxSpeedMetersPerSecond / (SwerveModuleConstants.kDriveEncoderDistancePerPulse),
      SwerveModuleConstants.kMaxAccelerationMetersPerSecond / (SwerveModuleConstants.kDriveEncoderDistancePerPulse));
    
    m_idealVelocity = profile.calculate(driveVelocityDesired, m_idealVelocity, Constants.kDt);

    // Calculate the turning motor output from the turning PID controller.
    final double turnOutput =
        MathUtil.clamp(
          m_turningPIDController.calculate(getPosR2d().getRadians(),
          state.angle.getRadians()),
           -1, 
           1
          );

    m_driveMotor.set(ControlMode.Velocity, m_idealVelocity/10.0);

    m_turningMotor.set(ControlMode.PercentOutput, turnOutput);
  }

  /** Zeroes all the SwerveModule encoders. */
  public void resetEncoders() {
    m_driveMotor.setSelectedSensorPosition(0, 0, 10);
    m_turningMotor.setSelectedSensorPosition(0, 0, 10);
    m_turningEncoder.setPosition(0, 10);
  }

  private Rotation2d getPosR2d() {
    Rotation2d encoderRotation = new Rotation2d(Math.toRadians(m_turningEncoder.getAbsolutePosition()));
    return encoderRotation.minus(m_encoderOffset);
  }
}
