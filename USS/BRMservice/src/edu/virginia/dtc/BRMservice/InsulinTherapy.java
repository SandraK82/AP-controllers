//*********************************************************************************************************************
//  Copyright 2011-2013 by the University of Virginia
//	All Rights Reserved
//
//  Created by Patrick Keith-Hynes
//  Center for Diabetes Technology
//  University of Virginia
//*********************************************************************************************************************
package edu.virginia.dtc.BRMservice;

import Jama.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.TimeZone;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Array;

import edu.virginia.dtc.SysMan.Debug;
import edu.virginia.dtc.SysMan.Event;
import edu.virginia.dtc.Tvector.Tvector;



public class InsulinTherapy {
	private boolean BUGS_FIXED = true;						// Simulate system operation before vaious bugs fixed
	private static final boolean DEBUG_MODE = true;
	private static final double FDA_MANDATED_MAXIMUM_CORRECTION_BOLUS = 3.0;
	private Context context;
	private Subject subject;
	private Bolus bolus_current;
	private Meal meal_current;
	public final String TAG = "BRMservice";
	// Identify owner of record in User Table 1
	public static final int MEAL_IOB_CONTROL = 10;
	// Interface definitions for the biometricsContentProvider
	public static final String PROVIDER_NAME = "edu.virginia.dtc.provider.biometrics";
    public static final Uri USER_TABLE_1_URI = Uri.parse("content://"+ PROVIDER_NAME + "/user1");

	// Parameter definitions
	double[][] DER_init = {{0.04}, {0.02}, {0.0}, {-0.02}, {-0.04}};
	Matrix MatDER = new Matrix(DER_init);
	double[][] DER2_init = {{0.0066}, {0.0055}, {0.0044}, {0.0033}, {0.0022}, {0.0011}, {0.0}, {-0.0011}, {-0.0022}, {-0.0033}, {-0.0044}, {-0.0055}, {-0.0066}};
	Matrix MatDER2 = new Matrix(DER2_init);
	double [][] BUFFER_A_init = {{0.7788}};
	Matrix MatBUFFER_A = new Matrix(BUFFER_A_init);
	double [][] BUFFER_B_init = {{0.2212}};
	Matrix MatBUFFER_B = new Matrix(BUFFER_B_init);
	double[][] MatINS_target_init = {{0.0}, {0.0}, {0.0}, {0.0}, {0.0}};
	Matrix MatINS_target = new Matrix(MatINS_target_init);
	double[][] MatCGM_slope1_init = {{0.0}, {0.0}, {0.0}, {0.0}, {0.0}};
	Matrix MatCGM_slope1 = new Matrix(MatCGM_slope1_init);
	double[][] MatCGM_slope2_init = {{0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}, {0.0}};
	Matrix MatCGM_slope2 = new Matrix(MatCGM_slope2_init);
	public static final int INS_targetslope_window_size_seconds = 27*60;		// Accommodate 5 data points: 25 minute window with 2 minutes of margin
	public static final int CGM_window_size_seconds = 62*60;		// Accommodate 8 data points: 60 minute window with 2 minutes of margin
	public static final int CGM_slope1_window_size_seconds = 27*60;				// Accommodate 5 data points: 25 minute window with 2 minutes of margin
	public static final int CGM_slope2_window_size_seconds = 67*60;				// Accommodate 13 data points: 65 minute window with 2 minutes of margin
	public final static double T2tgt = 30.0;
	public final static double PredH = 60.0;
	public final static double Finit = 60.0;
	public final static double Fthres = 0.2;
	public final static double BUFFER_A = 0.7788;
	public final static double BUFFER_B = 0.2212;
	
	// Store data in globals for logging to User Table 1
	double INS_target_sat_user1;
	double INS_target_slope_sat_user1;
	double differential_basal_rate_user1;
	double cgm_slope1_user1;
	double cgm_slope2_user1;
	double cgm_slope_diff_user1;
	double X_user1;
	double detect_meal_user1;
	
	// log_action priority levels
	private static final int LOG_ACTION_UNINITIALIZED = 0;
	private static final int LOG_ACTION_INFORMATION = 1;
	private static final int LOG_ACTION_DEBUG = 2;
	private static final int LOG_ACTION_NOT_USED = 3;
	private static final int LOG_ACTION_WARNING = 4;
	private static final int LOG_ACTION_SERIOUS = 5;
	
	//Variables created for glucose target calculation (glucose target can be tuned by changing these parameters)
	public static final int N_end=7;
	public static final int N_start=23;
	public static final int N_length=8;
	public final static int N_glucoseTarget=7;
	public static final double Taux=0.2;
	public static final double Gmax=160;
	public static final double Gspred=40;
	
	public boolean valid = false;

	public TherapyData therapy_data; 		// This class encapsulates the state variables that are being estimated and that must persist.

	public InsulinTherapy(
								Tvector Tvec_cgm1, 				// cgm1 history with time stamps in minutes since 1/1/1970
								Tvector Tvec_spent, 
								Tvector Tvec_IOB,
								Tvector Tvec_Gest,
								Tvector Tvec_Gbrakes,
								Params control_param, 
								long time,									// Seconds since 1/1/1970 
								int cycle_duration_mins,
								Context calling_context,
								double brakes_coeff,
								long calFlagTime) {
		context = calling_context;
		subject = new Subject(time, calling_context);
		meal_current = new Meal(calling_context);
		bolus_current = new Bolus(calling_context);
		log_action(TAG, "Create StateEstimate");
		// Initialize the class that holds our state data
		therapy_data = new TherapyData(time);
		// Generate the first state estimate
		if (insulin_therapy(Tvec_cgm1, Tvec_spent, Tvec_IOB, Tvec_Gest,Tvec_Gbrakes, control_param, time, cycle_duration_mins, brakes_coeff, calFlagTime))
			valid = true;
		else
			valid = false;
	}
	
	public boolean insulin_therapy(
			Tvector Tvec_cgm1, 							// cgm1 history with time stamps in minutes since 1/1/1970
			Tvector Tvec_spent, 
			Tvector Tvec_IOB,
			Tvector Tvec_Gest,
			Tvector Tvec_Gbrakes,
			Params control_param, 
			long time,									// Seconds since 1/1/1970 
			int cycle_duration_mins,
			double brakes_coeff,
			long calFlagTime) {
		boolean return_value = false;
		// 1. Update the subject data by reading latest profile values from the database
		subject.read(time, context);
		if (subject.valid == false)		// Protect against state estimates with uninitialized data.
			return false;
/*		
		// 2. Update the meal data by reading the latest meal record from the database
		meal_current.readActive();
		// 3. Calculate the spend_request for an active meal
		//    If the meal is active then find the matching bolus record
		if (meal_current.active) {
			// Value check brakes_coeff to make certain it is in range
			if (brakes_coeff > 1.0) {
				log_action(TAG, "Value exception in brakes_coeff: "+brakes_coeff, LOG_ACTION_WARNING);
				brakes_coeff = 1.0;
			}
			else if (brakes_coeff < 0.0) {
				log_action(TAG, "Value exception in brakes_coeff: "+brakes_coeff, LOG_ACTION_WARNING);
				brakes_coeff = 0.0;
			}
			if (bolus_current.read(meal_current.time)) {
				// Detect the glucose response to a meal if present
				if (detect_meal(time, Tvec_cgm1, brakes_coeff)) {
					// If a meal is detected then send the remaining insulin bolus all at once
					therapy_data.spend_request = bolus_current.MealBolusArem;
					bolus_current.MealBolusArem = 0.0;
					bolus_current.update();
					meal_current.active = false;
					meal_current.approved = false;
					meal_current.treated = true;
					meal_current.update();
					detect_meal_user1 = 1;
				}
				else {
					// If no meal detected yet then compute the next insulin priming bolus
					therapy_data.spend_request = primingInsulin(time, brakes_coeff);	
					detect_meal_user1 = 0;
				}
			}
			else {
				debug_message(TAG, "Error: InsulinTherapy.java active meal with time="+meal_current.time+" with no matching bolus time.");
				Log.e(TAG, "Error: InsulinTherapy.java active meal with time="+meal_current.time+" with no matching bolus time.");
				therapy_data.spend_request = 0.0;	
				return false;
			}
		}
		else {
			therapy_data.spend_request = 0.0;	
		}
*/		
		// 4. Compute the differential_basal_rate
		therapy_data.differential_basal_rate = differentialBasalRate(time, subject.basal, saturate_CF(subject.CF),Tvec_cgm1 ,Tvec_Gest, Tvec_Gbrakes ,Tvec_IOB, calFlagTime);
		// 5. Save internal data into user table 1
		storeUserTable1Data( time,
				 INS_target_sat_user1,
				 INS_target_slope_sat_user1,
				 therapy_data.differential_basal_rate, 
				 0.0,
				 0.0,
				 0.0,
				 0.0,
				 0.0,
				 0.0,
				 0.0,
				 0.0,
				 cgm_slope1_user1,
				 cgm_slope2_user1,
				 cgm_slope_diff_user1,
				 X_user1,
				 detect_meal_user1);
		return true;
	}

/*	
	public double primingInsulin(long time, double brakes_coeff) {
		double return_value = 0.0;
		// Is there an active meal?
		if (meal_current.active && !meal_current.treated) {
			// Is any meal insulin left undelivered?
			if (bolus_current.MealBolusArem > 0.0) {
				// Log the current state of the priming ramp
        		double corr_bolus = Math.max(0.0, 0.001*Math.round(1000.0*bolus_current.CORRA));
        		double total_priming = Math.max(0.0, 0.001*Math.round(1000.0*(bolus_current.MealBolusA-bolus_current.CORRA)));
        		double sent_priming = Math.max(0.0, 0.001*Math.round(1000.0*(bolus_current.MealBolusA-bolus_current.CORRA-bolus_current.MealBolusArem)));
				log_action(TAG, "Meal active > Correction="+corr_bolus+", Priming="+sent_priming+" U / "+total_priming+" U");
				// Have we reached the end of the ramp?
				double du, du0;
				if (time-meal_current.time < 60*bolus_current.d) {
					du0 = bolus_current.hmin + (bolus_current.Hmax-bolus_current.hmin)*(time-meal_current.time)/(60.0*bolus_current.d);
				}
				else {
					du0 = bolus_current.Hmax;
				}
				if (du0 > 3.0*subject.basal/60.0) {
					du = 3.0*brakes_coeff*subject.basal/60.0;
				}
				else {
					du = brakes_coeff*du0;
				}
				if (du < 0.0) {
					du = 0.0;		// No negative insulin requests!
				}
				return_value = du*5.0;
				debug_message(TAG, "return_value_1="+return_value);
				// This is the last priming bolus
				if (return_value > bolus_current.MealBolusArem) {
					return_value = bolus_current.MealBolusArem;
					meal_current.active = false;
					meal_current.approved = false;
					meal_current.treated = true;
					meal_current.update();
					bolus_current.MealBolusArem = 0.0;
					bolus_current.update();
				}
				else {
					bolus_current.MealBolusArem = bolus_current.MealBolusArem - return_value;
					bolus_current.update();
					meal_current.extended_bolus_insulin_rem = bolus_current.MealBolusArem;		// Copy undelivered insulin amount into this field so it can be accessed if meal cleared.
					meal_current.update();
				}
				subject.dump();
				meal_current.dump();
				bolus_current.dump();
			}
			else {
				meal_current.active = false;
				meal_current.approved = false;
				meal_current.treated = true;
				meal_current.update();
				bolus_current.MealBolusArem = 0.0;
				bolus_current.update();
			}
		}
		debug_message(TAG, "return_value_2="+return_value);
		return return_value;
	}

	private boolean detect_meal(long time, Tvector Tvec_cgm1, double brakes_coeff) {
		boolean return_value = false;
		// Check that the system has been collecting data for > 60 minutes, i.e. > 12 5-minute samples
		if (Tvec_cgm1.count() > 12) {
			double cgm_slope1 = glucoseSlope1(time, Tvec_cgm1);
			double cgm_slope2 = glucoseSlope2(time, Tvec_cgm1);
			double slope_diff = cgm_slope1-cgm_slope2;
			cgm_slope1_user1 = cgm_slope1;
			cgm_slope2_user1 = cgm_slope2;
			cgm_slope_diff_user1 = slope_diff;
			double X = BUFFER_A*BUFFER_B*brakes_coeff;
			X_user1 = X;
			if (meal_current.active && slope_diff*X>Fthres && meal_current.SMBG>90.0) {
				return_value = true;
			}
			if (return_value) {
				debug_message(TAG, "detect_meal = TRUE");
				log_action(TAG, "detect_meal = TRUE");
			}
			else {
				debug_message(TAG, "detect_meal = FALSE");
				log_action(TAG, "detect_meal = FALSE");
			}
			debug_message(TAG, "detect_meal > meal_current.active="+meal_current.active+", Fthres="+Fthres+", meal_current.SMBG="+meal_current.SMBG);
			debug_message(TAG, "detect_meal > cgm_slope1="+cgm_slope1+", cgm_slope2="+cgm_slope2+", slope_diff="+slope_diff+", return_value="+return_value);
			log_action(TAG, "detect_meal > meal_current.active="+meal_current.active+", Fthres="+Fthres+", meal_current.SMBG="+meal_current.SMBG);
			log_action(TAG, "detect_meal > cgm_slope1="+cgm_slope1+", cgm_slope2="+cgm_slope2+", slope_diff="+slope_diff+", return_value="+return_value);
		}
		return return_value;
	}
	
	// Return recent slope in mg/dl/min
	public double glucoseSlope1(long time, Tvector Tvec_cgm1) {
		double return_value = 0.0;
	   	List<Integer> indices;
	   	long t;
	   	double v;
	   	int ii;
	   	Tvector Tvec_cgm_slope1 = new Tvector(5);
	   	double cgm_slope1;
		if ((indices = Tvec_cgm1.getLastN(5)) != null) {
			debug_message(TAG, "glucoseSlope1 > indices.size()="+indices.size());
			// Initialize Tvec_glucose_target
			// - We have the 5 most recent cgm values
			try {
				Iterator<Integer> iterator = indices.iterator();
				int jj=0;
				while (iterator.hasNext()) {
					ii = iterator.next();
					t = Tvec_cgm1.get_time(ii);
					v = Tvec_cgm1.get_value(ii);
					Tvec_cgm_slope1.put(t, v);
					MatCGM_slope1.set(jj++, 0, v);
				}
				Tvec_cgm_slope1.dump(TAG, "Tvec_cgm_slope1");
				// Calculate cgm_target_slope
				cgm_slope1 = -MatDER.transpose().times(MatCGM_slope1).trace();
				debug_message(TAG, "cgm_slope1="+cgm_slope1);
				return_value = cgm_slope1;
			}
			catch (IllegalArgumentException ex) {
				debug_message(TAG, "glucoseSlope1 > Matrix inner dimensions must agree.");
				return 0.0;
			}
			catch (ArrayIndexOutOfBoundsException ex) {
				debug_message(TAG, "glucoseSlope1 > Array index out of bounds.");
				return 0.0;
			}
		} else {
			debug_message(TAG, "glucoseSlope1 > indices=null");
			return 0.0;
		}
		return return_value;
	}
	
	// Return previous slope in mg/dl/min
	public double glucoseSlope2(long time, Tvector Tvec_cgm1) {
		double return_value = 0.0;
	   	List<Integer> indices;
	   	long t;
	   	double v;
	   	int ii;
	   	Tvector Tvec_cgm_slope2 = new Tvector(13);
	   	double cgm_slope2;
		if ((indices = Tvec_cgm1.getLastN(13)) != null) {
			debug_message(TAG, "glucoseSlope2 > indices.size()="+indices.size());
			// Initialize Tvec_glucose_target
			// - We have the 13 most recent cgm values
			try {
				Iterator<Integer> iterator = indices.iterator();
				int jj=0;
				while (iterator.hasNext()) {
					ii = iterator.next();
					t = Tvec_cgm1.get_time(ii);
					v = Tvec_cgm1.get_value(ii);
					Tvec_cgm_slope2.put(t, v);
					MatCGM_slope2.set(jj++, 0, v);
				}
				Tvec_cgm_slope2.dump(TAG, "Tvec_cgm_slope2");
				// Calculate cgm_target_slope
				cgm_slope2 = -MatDER2.transpose().times(MatCGM_slope2).trace();
				debug_message(TAG, "cgm_slope2="+cgm_slope2);
				return_value = cgm_slope2;
			}
			catch (IllegalArgumentException ex) {
				debug_message(TAG, "glucoseSlope2 > Matrix inner dimensions must agree.");
				return 0.0;
			}
			catch (ArrayIndexOutOfBoundsException ex) {
				debug_message(TAG, "glucoseSlope2 > Array index out of bounds.");
				return 0.0;
			}
		} 
		else {
			debug_message(TAG, "glucoseSlope2 > indices=null");
			return 0.0;
		}
		return return_value;
	}
*/	
	
	
	public double glucoseTarget(long time) {
		// Get the offset in hours into the current day in the current time zone (based on cell phone time zone setting)
		TimeZone tz = TimeZone.getDefault();
		int UTC_offset_secs = tz.getOffset(time*1000)/1000;
		int timeNowMins = (int)((time+UTC_offset_secs)/60)%1440;
		double ToD_hours = (double)timeNowMins/60.0;
		double x;
		if (ToD_hours<N_end) {
			x = (ToD_hours+24-N_start)/N_length;
		}
		else if (ToD_hours>N_start)
		{
			x = (ToD_hours-N_start)/N_length;
		}
		else if ((ToD_hours>=N_end)&&(ToD_hours<=N_end+1))
		{
			x = 1-ToD_hours+N_end;
		}
		else 
		{
			x = 0;
		}
		double x1 = Math.pow((x/Taux),N_glucoseTarget)/(1.0+Math.pow((x/Taux),N_glucoseTarget));
		double glucose_target = Gmax-Gspred*x1;
		log_action(TAG, "ToD_hours="+String.format("%.2f", ToD_hours)+", glucose_target="+String.format("%.2f", glucose_target), LOG_ACTION_DEBUG);
		debug_message(TAG, "glucoseTarget > ToD_hours="+ToD_hours+", x="+x+", x1="+x1);
		debug_message(TAG, "glucoseTarget > time="+time+", ToD_hours="+ToD_hours+", glucose_target="+glucose_target);
		return glucose_target;
	}
	
	// Return differential basal rate in U/hour
	public double differentialBasalRate(long time, double basal, double CF,Tvector Tvec_cgm, Tvector Tvec_Gest,Tvector Tvec_Gbrakes,Tvector Tvec_IOB, long calFlagTime) {
    	final String FUNC_TAG = "differentialBasalRate";
		double return_value = 0.0;
	   	List<Integer> indices;
	   	long t;
	   	double v;
	   	int ii;
	   	//Tvector Tvec_insulin_target = new Tvector(5);
	   	double INS_target, INS_target_sat, INS_target_predicted;
	   	double INS_target_slope, INS_target_slope_sat;
	   	debug_message("VCU_test", "min 1800/TDI,CF == "+Double.toString(CF));
		if ((indices = Tvec_cgm.find(">", time-CGM_window_size_seconds, "<=", time)) != null) {
			debug_message("VCU_test", "cgm ind size "+indices.size());
			if (indices.size() != 0) {
				//No slope calculation!
				//Insulin Target is calculated based on the test : if we have 8 values or more in the last value (use G=Gbrakes (predicted glucose over 30 min)) 
				//If there is less than 8 values in the last hour, use the value: G=Gpred (in this case Gest)
				//Insulin target= (G-G_target)/min(1800/TDI,CF)
				try {
					if (indices.size() >= 8) {
						
						INS_target=(Tvec_Gbrakes.get_last_value()-glucoseTarget(Tvec_Gbrakes.get_last_time()))/CF;
					}
					else {
		        		
						INS_target=(Tvec_Gest.get_last_value()-glucoseTarget(Tvec_Gest.get_last_time()))/CF;
						
					}
					debug_message(TAG, "INS_target="+INS_target);
					// Calculate the predicted value of INS_target
					INS_target_sat = INS_target_saturate(INS_target, CF);
					//INS_target_slope_sat = INS_target_slope_saturate(INS_target_slope, time, calFlagTime);
					INS_target_predicted = INS_target_sat;
					INS_target_sat_user1 = INS_target_sat;
					//INS_target_slope_sat_user1 = INS_target_slope_sat;
					// Calculate a Rate
					double Rate = (INS_target_predicted - Math.max(Tvec_IOB.get_last_value(), 0.0))/T2tgt;
//					log_action(TAG, "differentialBasalRate > INS_tgt_pred="+INS_target_predicted+", IOB_sat="+Math.max(Tvec_IOB.get_last_value(), 0.0)+", INS_target="+INS_target, LOG_ACTION_DEBUG);
					debug_message("VCU_test", "Rate > "+Double.toString(Rate)+ "differentialBasalRate > INS_tgt_pred="+INS_target_predicted+", IOB_sat="+Math.max(Tvec_IOB.get_last_value(), 0.0)+", INS_target="+INS_target);
					
					// Calculate the differential basal rate
					double basal_ceiling=basal_saturate(Tvec_Gest.get_last_value(), basal);
					debug_message("VCU_test", "basal= "+Double.toString(basal)+" > basal ceiling = "+Double.toString(basal_ceiling));
					if (Rate > basal_ceiling/60.0) {
						return_value = basal_ceiling/60.0;
					}
					else if (Rate > 0.0 && Rate <= basal_ceiling/60.0) {
						return_value = Rate;
					}
					else {
						return_value = 0.0;
					}
					debug_message(TAG, "differentialBasalRate="+return_value);
					// Log glucose and insulin targets
					log_action(TAG, "INS_target="+String.format("%.2f", INS_target)+", INS_target_sat="+String.format("%.2f", INS_target_sat)+
							", IOB_sat="+String.format("%.2f", Math.max(Tvec_IOB.get_last_value(), 0.0))+
							", basal_ceiling="+String.format("%.2f", basal_ceiling)+", diff_rate="+String.format("%.2f", 60.0*return_value), Debug.LOG_ACTION_INFORMATION);
				}
				catch (IllegalArgumentException ex) {
            		Bundle b = new Bundle();
            		b.putString(	"description", "differentialBasalRate > Matrix inner dimensions must agree."+", "+FUNC_TAG
            					);
            		Event.addEvent(context, Event.EVENT_SYSTEM_ERROR, Event.makeJsonString(b), Event.SET_LOG);
					debug_message(TAG, "differentialBasalRate > Matrix inner dimensions must agree,"+", "+FUNC_TAG);
					return 0.0;
				}
				catch (ArrayIndexOutOfBoundsException ex) {
            		Bundle b = new Bundle();
            		b.putString(	"description", "differentialBasalRate > Array index out of bounds."+", "+FUNC_TAG
            					);
            		Event.addEvent(context, Event.EVENT_SYSTEM_ERROR, Event.makeJsonString(b), Event.SET_LOG);
					debug_message(TAG, "differentialBasalRate > Array index out of bounds,"+", "+FUNC_TAG);
					return 0.0;
				}
				catch (RuntimeException ex) {
            		Bundle b = new Bundle();
            		b.putString(	"description", "RuntimeException: "+ex.getMessage()+", "+FUNC_TAG
            					);
            		Event.addEvent(context, Event.EVENT_SYSTEM_ERROR, Event.makeJsonString(b), Event.SET_LOG);
					debug_message(TAG, "RuntimeException: "+ex.getMessage()+", "+FUNC_TAG);
				}
			} else {
        		Bundle b = new Bundle();
        		b.putString(	"description", "No Gest values in the last 27 minutes, differential_basal_rate=0"+", "+FUNC_TAG);
        		Event.addEvent(context, Event.EVENT_SYSTEM_ERROR, Event.makeJsonString(b), Event.SET_LOG);
				return 0.0;
			}
		} else {
    		Bundle b = new Bundle();
    		b.putString(	"description", "No Gest values in the last 27 minutes, differential_basal_rate=0"+", "+FUNC_TAG);
    		Event.addEvent(context, Event.EVENT_SYSTEM_ERROR, Event.makeJsonString(b), Event.SET_LOG);
			return 0.0;
		}
		return 60.0*return_value;
	}
	
	public double saturate_CF(double CF){
		final String FUNC_TAG = "saturate_CF";
		double TDI = get_adaptive_TDI();
		Debug.i(TAG, FUNC_TAG, "TDI === "+TDI);
		if (CF>(1800/TDI))
			return 1800/TDI;
		else if (CF<(1500/TDI))
			return 1500/TDI;
		else return CF;	
	}
	
	public double get_adaptive_TDI ()
	{
		final String FUNC_TAG = "get_adaptive_TDI";
		double adap_TDI;
		Settings st = IOMain.db.getLastTDIBrmDB();
		Debug.i(TAG, FUNC_TAG, "time= "+getCurrentTimeSeconds());
		Debug.i(TAG, FUNC_TAG, "BrmDB:time= "+st.time);
		Debug.i(TAG, FUNC_TAG, "BrmDB:TDI= "+st.TDI);
		if (st.TDI==0)
				return subject.TDI;
		else
			return st.TDI;
	}
	
	public double INS_target_saturate(double INS_target, double CF) {
		if (INS_target < 90.0/CF)
			return INS_target;
		else
			return (90.0/CF);
	}
	public double basal_saturate (double last_Gpred, double basal){
		
		final String FUNC_TAG = "basal_saturate";
		
		Settings st = IOMain.db.getLastBrmDB();
	

		Debug.i(TAG, FUNC_TAG, "time= "+getCurrentTimeSeconds());
		Debug.i(TAG, FUNC_TAG, "BrmDB:time= "+st.time);
		Debug.i(TAG, FUNC_TAG, "BrmDB:starttime= "+st.starttime);
		Debug.i(TAG, FUNC_TAG, "BrmDB:duration= "+st.duration);
		Debug.i(TAG, FUNC_TAG, "BrmDB:BGhigh= "+st.d1);
		Debug.i(TAG, FUNC_TAG, "BrmDB:BGlow= "+st.d2);
		Debug.i(TAG, FUNC_TAG, "BrmDB:d3= "+st.d3);
		
		double BGhigh = 0;
		double BGlow = 0;
		int mode = 1;   // set mode: 0 default(fixed); 1 changeable
		switch (mode) {
			case 0:
				BGhigh = 3;
				BGlow = 2;
				break;
			case 1:
				BGhigh = st.d1;
				BGlow = st.d2;
				break;
		}
		Debug.i(TAG, FUNC_TAG, "BGhigh= "+BGhigh+"   BGlow= "+BGlow);
		
		double saturated_basal=BGlow*basal;
		if (last_Gpred<180)
		{
			 saturated_basal =  BGlow*basal;
		}
		else if (last_Gpred>=180)
		{
			saturated_basal = BGhigh*basal;
		}
		return saturated_basal;
	}
	
//	public double basal_saturate (double last_Gpred, double basal){
//		int setting = IOMain.db.getLastBrmDB().setting;
//		int paramA, paramB;
//		
//		switch (setting) {
//		case 0:
//			paramA =2;
//			paramB =3;
//			break;
//		case 1:
//			paramA =1;
//			paramB =2;
//			break;
//		case 2:
//			paramA =1;
//			paramB =3;
//			break;
//		}
//		double saturated_basal=paramA*basal;
//		if (last_Gpred<180)
//		{
//			 saturated_basal =  paramA*basal;
//		}
//		else if (last_Gpred>=180)
//		{
//			saturated_basal = paramB*basal;
//		}
//		return saturated_basal;
//	}
	
	
	
	public double INS_target_slope_saturate(double INS_target_slope, long time, long calFlagTime) {
		if (time-calFlagTime > 30*60) {
			// The last calibration occurred more than 30 minutes ago
			if (INS_target_slope > 1.0/PredH)
				return 1.0/PredH;
			else if (INS_target_slope < -10.0/PredH)
				return -10.0/PredH;
			else
				return INS_target_slope;
		}
		else {
			// A calibration occurred within the last 30 minutes
			if (INS_target_slope > 1.0/PredH)
				return 0.0;
			else if (INS_target_slope < -10.0/PredH)
				return -10.0/PredH;
			else
				return INS_target_slope;
		}
	}	
		
	public long getCurrentTimeSeconds() {
		long currentTimeSeconds = (long)(System.currentTimeMillis()/1000);			// Seconds since 1/1/1970 in UTC
		return currentTimeSeconds;
	}
	
 	public void log_action(String service, String action) {
		Intent i = new Intent("edu.virginia.dtc.intent.action.LOG_ACTION");
        i.putExtra("Service", service);
        i.putExtra("Status", action);
        i.putExtra("time", (long)(System.currentTimeMillis()/1000));
        context.sendBroadcast(i);
	}
 	
	public void log_action(String service, String action, int priority) {
		Log.i(TAG, "LOG ACTION > "+action);
		
		Intent i = new Intent("edu.virginia.dtc.intent.action.LOG_ACTION");
        i.putExtra("Service", service);
        i.putExtra("Status", action);
        i.putExtra("priority", priority);
        i.putExtra("time", (long)(System.currentTimeMillis()/1000));
        context.sendBroadcast(i);
	}

	private static void debug_message(String tag, String message) {
		if (DEBUG_MODE) {
			Log.i(tag, message);
		}
	}
	
	private static void error_message(String tag, String message) {
		Log.e(tag, "Error: "+message);
	}
	
	public void storeUserTable1Data(long time,
			double INS_target_sat,
			double INS_target_slope_sat,
			double differential_basal_rate, 
			double MealBolusA,
			double MealBolusArem,
			double spend_request,
			double CorrA,
			double IOBrem,
			double d,
			double h,
			double H,
			double cgm_slope1,
			double cgm_slope2,
			double cgm_slope_diff,
			double X,
			double detect_meal) {
				ContentValues values = new ContentValues();
				values.put("time", time);
				values.put("l0", MEAL_IOB_CONTROL);
				values.put("d0", INS_target_sat);
				values.put("d1", INS_target_slope_sat);
				values.put("d2", differential_basal_rate);
				values.put("d3", MealBolusA);
				values.put("d4", MealBolusArem);
				values.put("d5", spend_request);
				values.put("d6", CorrA);
				values.put("d7", IOBrem);
				values.put("d8", d);
				values.put("d9", h);
				values.put("d10", H);
				values.put("d11", cgm_slope1);
				values.put("d12", cgm_slope2);
				values.put("d13", cgm_slope_diff);
				values.put("d14", X);
				values.put("d15", detect_meal);
				Uri uri;
				try {
					uri = context.getContentResolver().insert(USER_TABLE_1_URI, values);
				}
				catch (Exception e) {
					Log.e(TAG, e.getMessage());
				}		
		}
	
}
