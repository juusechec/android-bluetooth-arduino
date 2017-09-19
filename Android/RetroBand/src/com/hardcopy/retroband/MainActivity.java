/*
 * Copyright (C) 2014 The Retro Band - Open source smart band project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hardcopy.retroband;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;

import org.glud.Mail;

import com.hardcopy.retroband.contents.ActivityReport;
import com.hardcopy.retroband.contents.ContentObject;
import com.hardcopy.retroband.fragments.GraphFragment;
import com.hardcopy.retroband.fragments.IFragmentListener;
import com.hardcopy.retroband.fragments.LLFragmentAdapter;
import com.hardcopy.retroband.fragments.TimelineFragment;
import com.hardcopy.retroband.service.RetroBandService;
import com.hardcopy.retroband.utils.AppSettings;
import com.hardcopy.retroband.utils.Constants;
import com.hardcopy.retroband.utils.Logs;
import com.hardcopy.retroband.utils.RecycleUtils;
import com.hardcopy.retroband.utils.Utils;

import android.app.ActionBar;
import android.app.Activity;
import android.app.FragmentTransaction;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends FragmentActivity implements ActionBar.TabListener, IFragmentListener {

	// Debugging
	private static final String TAG = "RetroWatchActivity";

	// Context, System
	private Context mContext;
	private RetroBandService mService;
	private Utils mUtils;
	private ActivityHandler mActivityHandler;

	// Guardar en archivo.
	private boolean grabarArchivo = false;
	private String nombreArchivo = "no_fallout.txt";
	
	// Datos para consumir
	private int[] xRecord = new int[80];
	private int[] yRecord = new int[80];
	private int[] zRecord = new int[80];
	private int remainingRecords = 80;
	private Date lastDateArbol = new Date();
	private Date lastDatePendiente = new Date();
	private Date lastDateAmbos = new Date();
	private boolean estadoPendiente = false;
	private boolean estadoArbol = false;
	private boolean estadoSendMail = true;

	// Global

	// UI stuff
	private FragmentManager mFragmentManager;
	private LLFragmentAdapter mSectionsPagerAdapter;
	private ViewPager mViewPager;

	private ImageView mImageBT = null;
	private TextView mTextStatus = null;

	// Refresh timer
	private Timer mRefreshTimer = null;

	private double sumAnterior = -1;

	/*****************************************************
	 * Overrided methods
	 ******************************************************/

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// ----- System, Context
		mContext = this;// .getApplicationContext();
		mActivityHandler = new ActivityHandler();
		AppSettings.initializeAppSettings(mContext);

		setContentView(R.layout.activity_main);

		// Load static utilities
		mUtils = new Utils(mContext);

		// Set up the action bar.
		final ActionBar actionBar = getActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

		// Create the adapter that will return a fragment for each of the
		// primary sections of the app.
		mFragmentManager = getSupportFragmentManager();
		mSectionsPagerAdapter = new LLFragmentAdapter(mFragmentManager, mContext, this, mActivityHandler);

		// Set up the ViewPager with the sections adapter.
		mViewPager = (ViewPager) findViewById(R.id.pager);
		mViewPager.setAdapter(mSectionsPagerAdapter);

		// When swiping between different sections, select the corresponding
		// tab.
		mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
			@Override
			public void onPageSelected(int position) {
				actionBar.setSelectedNavigationItem(position);
			}
		});

		// For each of the sections in the app, add a tab to the action bar.
		for (int i = 0; i < mSectionsPagerAdapter.getCount(); i++) {
			// Create a tab with text corresponding to the page title defined by
			// the adapter.
			actionBar.addTab(actionBar.newTab().setText(mSectionsPagerAdapter.getPageTitle(i)).setTabListener(this));
		}

		// Setup views
		mImageBT = (ImageView) findViewById(R.id.status_title);
		mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_invisible));
		mTextStatus = (TextView) findViewById(R.id.status_text);
		mTextStatus.setText(getResources().getString(R.string.bt_state_init));

		// Do data initialization after service started and binded
		doStartService();
	}

	@Override
	public synchronized void onStart() {
		super.onStart();
	}

	@Override
	public synchronized void onPause() {
		super.onPause();
	}

	@Override
	public void onStop() {
		// Stop the timer
		if (mRefreshTimer != null) {
			mRefreshTimer.cancel();
			mRefreshTimer = null;
		}
		super.onStop();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		finalizeActivity();
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		// onDestroy is not always called when applications are finished by
		// Android system.
		finalizeActivity();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_scan:
			// Launch the DeviceListActivity to see devices and do scan
			doScan();
			return true;
		case R.id.action_discoverable:
			// Ensure this device is discoverable by others
			ensureDiscoverable();
			return true;
		}
		return false;
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed(); // TODO: Disable this line to run below code
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// This prevents reload after configuration changes
		super.onConfigurationChanged(newConfig);
	}

	/**
	 * Implements TabListener
	 */
	@Override
	public void onTabSelected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
		// When the given tab is selected, switch to the corresponding page in
		// the ViewPager.
		mViewPager.setCurrentItem(tab.getPosition());
	}

	@Override
	public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
	}

	@Override
	public void onTabReselected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
	}

	@Override
	public void OnFragmentCallback(int msgType, int arg0, int arg1, String arg2, String arg3, Object arg4) {
		switch (msgType) {
		case IFragmentListener.CALLBACK_RUN_IN_BACKGROUND:
			if (mService != null)
				mService.startServiceMonitoring();
			break;

		default:
			break;
		}
	}

	/*****************************************************
	 * Private methods
	 ******************************************************/

	/**
	 * Service connection
	 */
	private ServiceConnection mServiceConn = new ServiceConnection() {

		public void onServiceConnected(ComponentName className, IBinder binder) {
			Log.d(TAG, "Activity - Service connected");

			mService = ((RetroBandService.LLServiceBinder) binder).getService();

			// Activity couldn't work with mService until connections are made
			// So initialize parameters and settings here, not while running
			// onCreate()
			initialize();
		}

		public void onServiceDisconnected(ComponentName className) {
			mService = null;
		}
	};

	/**
	 * Start service if it's not running
	 */
	private void doStartService() {
		Log.d(TAG, "# Activity - doStartService()");
		startService(new Intent(this, RetroBandService.class));
		bindService(new Intent(this, RetroBandService.class), mServiceConn, Context.BIND_AUTO_CREATE);
	}

	/**
	 * Stop the service
	 */
	private void doStopService() {
		Log.d(TAG, "# Activity - doStopService()");
		mService.finalizeService();
		stopService(new Intent(this, RetroBandService.class));
	}

	/**
	 * Initialization / Finalization
	 */
	private void initialize() {
		Logs.d(TAG, "# Activity - initialize()");
		mService.setupService(mActivityHandler);

		// If BT is not on, request that it be enabled.
		// RetroWatchService.setupBT() will then be called during
		// onActivityResult
		if (!mService.isBluetoothEnabled()) {
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, Constants.REQUEST_ENABLE_BT);
		}

		// Load activity reports and display
		if (mRefreshTimer != null) {
			mRefreshTimer.cancel();
		}

		// Use below timer if you want scheduled job
		// mRefreshTimer = new Timer();
		// mRefreshTimer.schedule(new RefreshTimerTask(), 5*1000);
	}

	private void finalizeActivity() {
		Logs.d(TAG, "# Activity - finalizeActivity()");

		if (!AppSettings.getBgService()) {
			doStopService();
		} else {
		}

		// Clean used resources
		RecycleUtils.recursiveRecycle(getWindow().getDecorView());
		System.gc();
	}

	/**
	 * Launch the DeviceListActivity to see devices and do scan
	 */
	private void doScan() {
		Intent intent = new Intent(this, DeviceListActivity.class);
		startActivityForResult(intent, Constants.REQUEST_CONNECT_DEVICE);
	}

	/**
	 * Ensure this device is discoverable by others
	 */
	private void ensureDiscoverable() {
		if (mService.getBluetoothScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(intent);
		}
	}
	
	public void procesarAmbosAlgoritmos() {
		Date actualDate = new Date();
		long secondsBetween = (actualDate.getTime() - lastDateAmbos.getTime()) / 1000;
		if (estadoArbol && estadoPendiente && secondsBetween > 3) {
			String mensaje = "La persona se ha caido 100% fiable.";
			enviarEmailPersonalizado("Emergencia Caida (Ambos al Tiempo).", mensaje);
			lastDateAmbos = new Date();
		}
	}

	public void procesarXYZ(int[] accel) {
		if (accel == null || accel.length < 3)
			return;
		// Viene un arreglo de [x1,y1,z1,x2,y2,z2,x3,y3,z3,...,x_n,y_n,z_n]
		TextView textIndicatorView = (TextView) findViewById(R.id.text_indicador);
		textIndicatorView.setText("Listo");
		textIndicatorView.setBackgroundColor(Color.parseColor("#6495ED")); //Azul
		
		for(int i=0; i<accel.length; i+=3) {			
			int x = accel[i];
			int y = accel[i+1];
			int z = accel[i+2];
			int[] vector = {x,y,z};
			procesarSum(vector);
			appendRecordVector(vector);
			procesarMuestra();//se puede ejecutar en cualquier lado
		}
		
		if (grabarArchivo == true) {
			guardarDatos(accel);
		} else {
			// actualiza el nombre con la fecha
			Date date = new Date();
			DateFormat hourdateFormat = new SimpleDateFormat("dd_MM_yyyy__HH_mm_ss");
			System.out.println("Hora y fecha: " + hourdateFormat.format(date));
			nombreArchivo = "no_fallout_" + hourdateFormat.format(date) + ".txt";
		}
	}

	public void procesarSum(int[] vector) {
		// vector es un arreglo de 3 [x_#,y_#,z_#]
		TextView textLimiteView = (TextView) findViewById(R.id.text_limite);
		textLimiteView.setText("Sumando...");

		EditText editLimiteView = (EditText) findViewById(R.id.edit_limite);

		double sum = this.magnitudSum(vector);
		double pendiente = sumAnterior - sum;
		// si no está definido, se usa el predeterminado de int limite=25000
		textLimiteView.setText("Pend: " + String.valueOf(Math.abs(pendiente)));
		int limite = 7000;
		String valor = (String) editLimiteView.getText().toString();
		if (!valor.equals("")) {
			try {
				limite = Integer.parseInt(valor);
			} catch (Exception ex) {
				Log.e("Cast", "Valor : " + valor);
			}
		}
		Date actualDate = new Date();
		long secondsBetween = (actualDate.getTime() - lastDatePendiente.getTime()) / 1000;
		
		if (sumAnterior != -1 && Math.abs(pendiente) > limite && secondsBetween > 2) {// Se cayo la persona
			if (estadoSendMail) {
				String mensaje = "Alerta valor absoluto pendiente: " + String.valueOf(Math.abs(pendiente));
				mensaje += "\r\nEnergia X: " + String.valueOf(this.getEnergy(this.getXRecord()));
				mensaje += "\r\nEnergia Y: " + String.valueOf(this.getEnergy(this.getYRecord()));
				mensaje += "\r\nEnergia Z: " + String.valueOf(this.getEnergy(this.getZRecord()));
				mensaje += "\r\nEnergia VecSum: " + String.valueOf(this.getEnergy(this.getVsumRecord()));
				// probando con transformada wavelet haar
				double[] muestraVsum = getVsumRecord();
				double[] coeficientes = getCoef(muestraVsum);
				mensaje += "\r\nCoeficientes:\r\n"+arrayToString(coeficientes);
				enviarEmailPersonalizado("Alerta Caida (Umbral de la Derivada del Vector Suma).", mensaje);
				
			}
			textLimiteView.setBackgroundColor(Color.parseColor("#FF0000"));//Rojo
			lastDatePendiente = new Date();
			estadoPendiente = true;
		} else if (secondsBetween > 2) {// Todo esta bien
			textLimiteView.setBackgroundColor(Color.parseColor("#00FF00"));//Verde
			estadoPendiente = false;
		}
		sumAnterior = sum;
	}
	
	public int getEnergy(int[] vector) {
		// http://www.unet.edu.ve/aula10c/Asenales/Unid01/seg02.htm
		int sum = 0;
		for (int i = 0; i < vector.length; i++) {
			sum += Math.pow(vector[i], 2);// sin abs, porque es real
		}
		return sum;
	}
	
	public double getEnergy(double[] vector) {
		int convertedVector[] = new int[vector.length];
		for (int i = 0; i < vector.length; i++) {
			convertedVector[i] = (int)vector[i];
		}
		return (double)this.getEnergy(convertedVector);
	}
	
	public double magnitudSum(int[] vector) {
		return Math.sqrt(Math.pow(vector[0], 2) + Math.pow(vector[1], 2) + Math.pow(vector[2], 2));
	}

	public void guardarDatos(int[] accel) {
		try {
			File ruta_sd = Environment.getExternalStorageDirectory();

			File f = new File(ruta_sd.getAbsolutePath(), nombreArchivo);

			FileOutputStream fos = new FileOutputStream(f, true);// new
																	// FileOutputStream(f);override

			OutputStreamWriter fout = new OutputStreamWriter(fos);
			
			for(int i=0; i<accel.length; i+=3) {				
				String texto = String.valueOf(accel[i]) + "," + String.valueOf(accel[i+1]) + "," + String.valueOf(accel[i+2])
				+ "\n";
				// System.out.print(texto);
				fout.write(texto);
			}
			fout.close();
		} catch (Exception ex) {
			Log.e("Ficheros", "Error al escribir fichero a tarjeta SD");
		}
	}
	
	public int[] getXRecord() {
		if (this.remainingRecords == 0) {
			return this.xRecord;
		} else {
			return new int[0];
		}
	}
	
	public int[] getYRecord() {
		if (this.remainingRecords == 0) {
			return this.yRecord;
		} else {
			return new int[0];
		}
	}
	
	public int[] getZRecord() {
		if (this.remainingRecords == 0) {
			return this.zRecord;
		} else {
			return new int[0];
		}
	}
	
	public double[] getVsumRecord() {
		int[] muestraX = getXRecord();
		int[] muestraY = getYRecord();
		int[] muestraZ = getZRecord();
		double[] muestraVsum = new double[muestraX.length];
		for (int i = 0; i < muestraX.length; i++) {
			int [] vector = {muestraX[i], muestraX[i], muestraX[i]};
			muestraVsum[i] = this.magnitudSum(vector);
		}
		return muestraVsum;
	}
	
	public void appendRecordVector(int[] vector) {
		addToRecordSlice(vector);
		if (!(this.remainingRecords == 0)) {// si no está lleno, sigue disminuyendo remainingRecords
			// reduce en 1 que el Record este lleno
			this.remainingRecords = this.remainingRecords-1;
		}
	}
	
	private void addToRecordSlice(int[] vector) {
		int[] xCuted = Arrays.copyOfRange(this.xRecord, 1, this.xRecord.length);
		int[] yCuted = Arrays.copyOfRange(this.yRecord, 1, this.yRecord.length);
		int[] zCuted = Arrays.copyOfRange(this.zRecord, 1, this.zRecord.length);
		int[] xVector = {vector[0]};
		int[] yVector = {vector[1]};
		int[] zVector = {vector[2]};
		
		this.xRecord = concatenate(xCuted, xVector);
		this.yRecord = concatenate(yCuted, yVector);
		this.zRecord = concatenate(zCuted, zVector);
		// https://stackoverflow.com/questions/11001720/get-only-part-of-an-array-in-java
	}
	
	// Generic function to merge arrays of same type in Java
	public static int[] concatenate(int[] first, int[] second){
		//example: int[] a = {1,2,3}; int[] b = {5,6,7}; int [] c = concatenate(a,b);
		int[] ob = (int[]) Array.newInstance(first.getClass().getComponentType(),                      
					first.length + second.length);
		System.arraycopy(first, 0, ob, 0, first.length);
		System.arraycopy(second, 0, ob, first.length, second.length);
		return ob;
	}
	
	public void procesarMuestra() {
		TextView textIndicadorView = (TextView) findViewById(R.id.text_indicador);
		textIndicadorView.setText("Procesando...");
		
		int[] muestraX = getXRecord();
		int[] muestraY = getYRecord();
		if (muestraX.length > 0 && muestraY.length > 0) {
			int maxX = getMax(muestraX);
			int minY = getMin(muestraY);
			//double varX = getVariance(muestraX);
			//double varY = getVariance(muestraY);
			
			textIndicadorView.setText("Terminado!");
			boolean fallout = false;
			
//			if (varY < 4449) {
//				if (varY >= 59) {
//					fallout = false;
//				} else {
//					if (varX < 76) {
//						fallout = false;
//					} else {
//						fallout = true;
//					}
//				}
//			} else {
//				fallout = true;
//			}
			
			if (minY >= 1184) {
				if (minY < 16000) {
					fallout = false;
				} else {
					if (maxX >= 562) {
						fallout = false;
					} else {
						fallout = true;
					}
				}
			} else {
				fallout = true;
			}
			
			textIndicadorView.setText("MaxX:"+String.valueOf(maxX)+", MinY:"+String.valueOf(minY));
			if (fallout == true) { // se cayó
				Date actualDate = new Date();
				long secondsBetween = (actualDate.getTime() - lastDateArbol.getTime()) / 1000;
				if (secondsBetween > 5) { // mayor a 5 seg
					if (estadoSendMail) {
						String mensaje = "Alerta variables: " +"MaxX:"+String.valueOf(maxX)+", MinY:"+String.valueOf(minY);
						mensaje += "\r\nEnergia X: " + String.valueOf(this.getEnergy(this.getXRecord()));
						mensaje += "\r\nEnergia Y: " + String.valueOf(this.getEnergy(this.getYRecord()));
						mensaje += "\r\nEnergia Z: " + String.valueOf(this.getEnergy(this.getZRecord()));
						mensaje += "\r\nEnergia VecSum: " + String.valueOf(this.getEnergy(this.getVsumRecord()));
						// probando con transformada wavelet haar
						double[] muestraVsum = getVsumRecord();
						double[] coeficientes = getCoef(muestraVsum);
						mensaje += "\r\nCoeficientes:\r\n"+arrayToString(coeficientes);
						enviarEmailPersonalizado("Alerta Caida (Arbol de Decisiones con Varianza).", mensaje);
					}
					lastDateArbol = new Date(); // hora actual
				}				
				textIndicadorView.setBackgroundColor(Color.parseColor("#FF0000")); // Rojo
				estadoArbol = true;
			} else {
				textIndicadorView.setBackgroundColor(Color.parseColor("#00FF00")); // Verde
				estadoArbol = false;
			}			
		}
	}
	
	public static int getMax(int[] vectoxAxis) {
		int max = vectoxAxis[0];
		for(int i=1;i<vectoxAxis.length;i++) {
		    max = Math.max(vectoxAxis[i], max);
		}
		return max;
	}
	
	public static int getMin(int[] vectoxAxis) {
		int min = vectoxAxis[0];
		for(int i=1;i<vectoxAxis.length;i++) {
		    min = Math.min(vectoxAxis[i], min);
		}
		return min;
	}
	
	public static double getMean(int [] vectorAxis) {
		double sum = 0;
		for (int i = 0; i < vectorAxis.length; i++) {
			sum += vectorAxis[i];
		}
		return sum/vectorAxis.length;
	}
	
	public static double getVariance(int [] vectorAxis) {
		double mean = getMean(vectorAxis);
		double sumatory = 0; 
		for (int i = 0; i < vectorAxis.length; i++) {
			sumatory += Math.pow(vectorAxis[i] - mean, 2);
		}
		return sumatory/vectorAxis.length;
	}
	
	public static String arrayToString(double[] myArray) {
		String text = "";
		for (int i = 0; i < myArray.length; i++) {
			text += String.valueOf(myArray[i]) + "\t";
		}
		return text;
	}
	
	public static double[] getCoef(double[] vector) {
		double[][] matrizT = {
			{ 1, 1, 0, 0, 0, 0, 0, 0 },
			{ 1, -1, 0, 0, 0, 0, 0, 0 },
			{ 0, 0, 1, 1, 0, 0, 0, 0 },
			{ 0, 0, 1, -1, 0, 0, 0, 0 },
			{ 0, 0, 0, 0, 1, 1, 0, 0 },
			{ 0, 0, 0, 0, 1, -1, 0, 0 },
			{ 0, 0, 0, 0, 0, 0, 1, 1 },
			{ 0, 0, 0, 0, 0, 0, 1, -1 }
		};
		int multiplo = (int) (vector.length / 8);
		double[][] matrizS = new double[8][multiplo];
		int indice = 0;
		for (int j = 0; j < multiplo; j++) {
			for (int i = 0; i < 8; i++) {
				matrizS[i][j] = vector[indice];
				indice++;
			}
		}
//		System.out.println("MT------------------");
//		printMatrix(matrizT);
//		System.out.println("MS------------------");
//		printMatrix(matrizS);
		double[][] x = multMatrix(matrizT, matrizS);
		x = multMatrixNumber(x, 1 / Math.sqrt(2));
//		System.out.println("XX-----------------");
//		printMatrix(x);
//		System.out.println("------------------");
		int size = (x.length * x[0].length) / 2;
		double[] coef = new double[size];
		int contador = 0;
		indice = 0;
		for (int j = 0; j < x[0].length; j++) {
			for (int i = 0; i < x.length; i++) {			
				if (contador % 2 == 0) {// par
					coef[indice++] = x[i][j];
				}
				contador++;
			}
		}
		return coef;
	}

	/**
	 * Multiplica dos matrices
	 * 
	 * @param int[][]
	 *            A
	 * @param int[][]
	 *            B
	 * @return int[][] producto
	 */
	private static double[][] multMatrix(double a[][], double b[][]) {
		int aRows = a.length, aColumns = a[0].length, bRows = b.length, bColumns = b[0].length;

		if (aColumns != bRows) {
			throw new IllegalArgumentException("A:Rows: " + aColumns + " did not match B:Columns " + bRows + ".");
		}

		double[][] resultant = new double[aRows][bColumns];

		for (int i = 0; i < aRows; i++) { // aRow
			for (int j = 0; j < bColumns; j++) { // bColumn
				for (int k = 0; k < aColumns; k++) { // aColumn
					resultant[i][j] += a[i][k] * b[k][j];
				}
			}
		}

		return resultant;
	}
	
	private static double[][] multMatrixNumber(double a[][], double x) {
		int aRows = a.length, aColumns = a[0].length;
		double[][] resultant = new double[aRows][aColumns];
		
		for (int i = 0; i < aRows; i++) { // aRow
			for (int j = 0; j < aColumns; j++) { // bColumn
				resultant[i][j] += a[i][j] * x;
			}
		}
		return resultant;
	}
	
	private static void printMatrix(double a[][]) {
		for (int i = 0; i < a.length; i++) {
			for (int j = 0; j < a[0].length; j++) {
				System.out.print(a[i][j]);
				System.out.print(" ");
			}
			System.out.println();
		}
	}

	/*****************************************************
	 * Public classes
	 ******************************************************/

	/**
	 * Receives result from external activity
	 */
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		Logs.d(TAG, "onActivityResult " + resultCode);

		switch (requestCode) {
		case Constants.REQUEST_CONNECT_DEVICE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				// Get the device MAC address
				String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				// Attempt to connect to the device
				if (address != null && mService != null)
					mService.connectDevice(address);
			}
			break;

		case Constants.REQUEST_ENABLE_BT:
			// When the request to enable Bluetooth returns
			if (resultCode == Activity.RESULT_OK) {
				// Bluetooth is now enabled, so set up a BT session
				mService.setupBT();
			} else {
				// User did not enable Bluetooth or an error occured
				Logs.e(TAG, "BT is not enabled");
				Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
			}
			break;
		} // End of switch(requestCode)
	}

	/*****************************************************
	 * Handler, Callback, Sub-classes
	 ******************************************************/

	public class ActivityHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			// BT state messages
			case Constants.MESSAGE_BT_STATE_INITIALIZED:
				mTextStatus.setText(getResources().getString(R.string.bt_title) + ": "
						+ getResources().getString(R.string.bt_state_init));
				mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_invisible));
				break;
			case Constants.MESSAGE_BT_STATE_LISTENING:
				mTextStatus.setText(getResources().getString(R.string.bt_title) + ": "
						+ getResources().getString(R.string.bt_state_wait));
				mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_invisible));
				break;
			case Constants.MESSAGE_BT_STATE_CONNECTING:
				mTextStatus.setText(getResources().getString(R.string.bt_title) + ": "
						+ getResources().getString(R.string.bt_state_connect));
				mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_away));
				break;
			case Constants.MESSAGE_BT_STATE_CONNECTED:
				if (mService != null) {
					String deviceName = mService.getDeviceName();
					if (deviceName != null) {
						mTextStatus.setText(getResources().getString(R.string.bt_title) + ": "
								+ getResources().getString(R.string.bt_state_connected) + " " + deviceName);
						mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_online));
					}
				}
				break;
			case Constants.MESSAGE_BT_STATE_ERROR:
				mTextStatus.setText(getResources().getString(R.string.bt_state_error));
				mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_busy));
				break;

			// BT Command status
			case Constants.MESSAGE_CMD_ERROR_NOT_CONNECTED:
				mTextStatus.setText(getResources().getString(R.string.bt_cmd_sending_error));
				mImageBT.setImageDrawable(getResources().getDrawable(android.R.drawable.presence_busy));
				break;

			////////////////////////////////////////////
			// Contents changed
			////////////////////////////////////////////
			case Constants.MESSAGE_READ_ACCEL_REPORT:
				ActivityReport ar = (ActivityReport) msg.obj;
				if (ar != null) {
					TimelineFragment frg = (TimelineFragment) mSectionsPagerAdapter
							.getItem(LLFragmentAdapter.FRAGMENT_POS_TIMELINE);
					frg.showActivityReport(ar);
				}
				break;

			case Constants.MESSAGE_READ_ACCEL_DATA:
				ContentObject co = (ContentObject) msg.obj;
				if (co != null) {
					GraphFragment frg = (GraphFragment) mSectionsPagerAdapter
							.getItem(LLFragmentAdapter.FRAGMENT_POS_GRAPH);
					frg.drawAccelData(co.mAccelData);
					procesarXYZ(co.mAccelData);
				}
				break;

			default:
				break;
			}

			super.handleMessage(msg);
		}
	} // End of class ActivityHandler

	/**
	 * Auto-refresh Timer
	 */
	private class RefreshTimerTask extends TimerTask {
		public RefreshTimerTask() {
		}

		public void run() {
			mActivityHandler.post(new Runnable() {
				public void run() {
					// TODO:
					mRefreshTimer = null;
				}
			});
		}
	}

	/** Called when the user touches the button */
	public void onClickButtonEmail(View view) {
		// Do something in response to button click
		enviarEmail();
	}

	public void enviarEmail() {
		String[] correos = { "dibujatuvida-conpasion@yahoo.es", "jamesbuck9203@hotmail.com", "diego.andresmancilla@hotmail.com" };
		SendEmailAsyncTask email = new SendEmailAsyncTask();
		email.activity = this;
		email.m = new Mail();
		email.m.set_to(correos); //Enviar a correos.
		email.execute();
	}
	
	public void enviarEmailPersonalizado(String asunto, String mensaje) {
		String[] correos = { "dibujatuvida-conpasion@yahoo.es", "jamesbuck9203@hotmail.com", "diego.andresmancilla@hotmail.com" };
		SendEmailAsyncTask email = new SendEmailAsyncTask();
		email.activity = this;
		email.m = new Mail();
		email.m.set_to(correos); //Enviar a correos.
		email.m.set_subject(asunto); //Establecer asunto
		email.m.set_body(mensaje); //Establecer mensaje
		email.execute();
	}
	/* Termina */

	/* Empieza boton 2, solo se necesita cambiar nombre */
	/** Called when the user touches the button */
	public void onClickButtonRecord(View view) {
		// Do something in response to button click
		Button p1_button = (Button)findViewById(R.id.buttonRecord);
		if (grabarArchivo) {
			grabarArchivo = false;
			displayMessage("Desactivado");
			p1_button.setText("Grabar");
		} else {
			grabarArchivo = true;
			displayMessage("Activado");
			displayMessage("Guardando como: " + nombreArchivo);
			p1_button.setText("Detener");
		}
	}
	
	public void onClickButtonOnOffMail(View view) {	
		if (estadoSendMail) {
			estadoSendMail = false;
			displayMessage("Desactivado");
		} else {
			estadoSendMail = true;
			displayMessage("Activado");
		}
	}

	public void displayMessage(final String mensaje) {
		Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_SHORT).show();
	}
}

// https://developer.android.com/reference/android/os/AsyncTask.html
class SendEmailAsyncTask extends AsyncTask<Void, Void, Boolean> {
	Mail m;
	MainActivity activity;

	public SendEmailAsyncTask() {
	}

	@Override
	protected Boolean doInBackground(Void... params) {
		displayMessage("Enviando...");
		try {
			if (m.send()) {
				displayMessage("Email enviado.");
			} else {
				displayMessage("Fallo al enviar email.");
			}
			return true;
		} catch (AuthenticationFailedException e) {
			Log.e(SendEmailAsyncTask.class.getName(), "Bad account details");
			e.printStackTrace();
			displayMessage("Authentication failed.");
			return false;
		} catch (MessagingException e) {
			Log.e(SendEmailAsyncTask.class.getName(), "Email failed");
			e.printStackTrace();
			displayMessage("Email failed to send.");
			return false;
		} catch (Exception e) {
			e.printStackTrace();
			displayMessage("Unexpected error occured.");
			Log.e("EMAIL", "exception: " + e.getMessage());
			Log.e("EMAIL", "exception: " + e.toString());
			return false;
		}
	}

	public void displayMessage(final String mensaje) {
		activity.runOnUiThread(new Runnable() {
			public void run() {
				Toast.makeText(activity.getApplicationContext(), mensaje, Toast.LENGTH_SHORT).show();
			}
		});
	}

}