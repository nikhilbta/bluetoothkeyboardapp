package com.example.btkbv2;

import static android.bluetooth.BluetoothHidDevice.SUBCLASS1_KEYBOARD;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Set;


@SuppressLint("MissingPermission")
public class Controller extends Activity implements UpdateView {

    private BroadcastReceiver receiver;

    private BluetoothPermissionManager bluetoothPermissionManager;
    private Model model;
    private UpdateView UView;

    private String inputValue;
    private int regState;
    private boolean isRepeatActive = false;
    private String lastInput = ""; // To store the last input before repeating


    private Spinner inputs;
    private Spinner passwords;

    private TextInputEditText textInputEditText;

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bluetoothPermissionManager = new BluetoothPermissionManager(this, this);
        bluetoothPermissionManager.checkAndRequestPermissions();


        UView = new Controller();
        model = new Model(this, UView);

        inputs = ((Activity) this).findViewById(R.id.inputs);
        passwords = ((Activity) this).findViewById(R.id.passwords);

        SharedPreferences prefs = getSharedPreferences("BTKBV2", MODE_PRIVATE);
        inputValue = prefs.getString("input_value", "");


        getProxy();
        initializePairedSpinner();
        initializeAvailableSpinner();
        findAvailableDevices();
        spinnerListener();


        textInputEditText = findViewById(R.id.TextInputEditLayout);
        textInputEditText.setText(inputValue);

        findViewById(R.id.inputbutton).setOnClickListener(v -> {
            inputValue = textInputEditText.getText().toString();
            if(model.getTargetDevice() != null && model.getHidDevice().getConnectionState(model.getTargetDevice()) == BluetoothProfile.STATE_CONNECTED){
                try {
                    model.convertTextToHidReport(inputValue);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                Log.d("mainpain", "Input value: " + inputValue);
                textInputEditText.setText("");
            }
            else {
                toastMessage(Controller.this, "No Device Connected");
            }
        });


        findViewById(R.id.repeat_input).setOnClickListener(v -> {
            // Check if repeat is currently active
            if (!isRepeatActive) {
                // Save the current text before repeating
                lastInput = textInputEditText.getText().toString();

                // Repeat the input value
                textInputEditText.setText(inputValue);

                // Set flag to indicate repeat is active
                isRepeatActive = true;
            } else {
                // Undo the repeat by restoring the last input
                textInputEditText.setText(lastInput);

                // Set flag to indicate repeat is no longer active
                isRepeatActive = false;
            }
        });

        findViewById(R.id.edit).setOnClickListener(v -> {

        });



    }

    @Override
    protected void onResume() {
        super.onResume();
        getProxy();
        Log.d("mainpain", "on Resume");
        if(model.getTargetDevice() != null && model.getHidDevice() != null){
            Log.d("mainpain", "TD: " + model.getTargetDevice().getName() + "HID: " + model.getHidDevice());
            connect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveValues();
        if(model.getHidDevice() != null & model.getTargetDevice() != null) {
            model.getHidDevice().disconnect(model.getTargetDevice());
        }
        Log.d("mainpain", "on Pause");
    }

    protected void onDestroy() {
        super.onDestroy();
        saveValues();
        model.getHidDevice().disconnect(model.getTargetDevice());
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    private void getProxy() {
        model.getBtAdapter().getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @SuppressLint("MissingPermission")
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
                        @Override
                        public void onConnectionStateChanged(BluetoothDevice device, final int state) {
                            if (device.equals(model.getTargetDevice())) {
                                Runnable statusUpdateRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                                    logMessage("mainpain", "HID Device currently disconnected from: " + device.getName());
                                                } else if (state == BluetoothProfile.STATE_CONNECTING) {
                                                    toastMessage(Controller.this, "Connecting...");
                                                } else if (state == BluetoothProfile.STATE_CONNECTED) {
                                                    toastMessage(Controller.this, "Connected");
                                                } else if (state == BluetoothProfile.STATE_DISCONNECTING) {
                                                    logMessage("mainpain", "HID Device currently disconnecting from: " + device.getName());
                                                }
                                            }
                                        });
                                    }
                                };

                                // Create a Thread using the Runnable variable
                                Thread statusUpdateThread = new Thread(statusUpdateRunnable);

                                // Start the thread
                                statusUpdateThread.start();
                            }
                        }

                        @Override
                        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                            super.onAppStatusChanged(pluggedDevice, registered);
                            UView.logMessage("mainpain", registered ? "HID Device registered successfully" : "HID Device registration failed");
                            if(registered){
                                //model.pairedDevicePicked(model.getPairedDevicesList().indexOf(model.getTargetDevice()));
                                regState = 1;
                            }
                            else{
                                regState = 0;
                            }
                        }

                        @Override
                        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
                            UView.logMessage("mainpain", "onGetReport: device=" + device + " type=" + type
                                    + " id=" + id + " bufferSize=" + bufferSize);
                        }

                        @Override
                        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] report) {
                            UView.logMessage("mainpain", "onSetReport: device=" + device + " type=" + type
                                    + " id=" + id + " report length=" + (report != null ? report.length : "null"));
                        }
                    };
                    model.registerHidDevice(proxy, callback);
                }
            }


            @Override
            public void onServiceDisconnected(int profile) {
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    private void spinnerListener() {
        model.getPairedDevicesSpinner().setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                model.pairedDevicePicked(position);
                if(model.getTargetDevice() != null && model.getHidDevice() != null){
                    Log.d("mainpain", "testing");
                    connect();
                }
            }


            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        model.getAvailableDevicesSpinner().setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                model.availableDevicePicked(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        model.getAvailableDevicesSpinner().setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                findAvailableDevices();
                return false;
            }
        });



    }

    private void initializePairedSpinner() {
        Set<BluetoothDevice> pairedDevicesSet = model.getBtAdapter().getBondedDevices();
        ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();

        pairedDevices.addAll(pairedDevicesSet);

        model.updatePairedDevicesSpinnerModel(pairedDevices);

    }

    private void initializeAvailableSpinner() {
        ArrayList<BluetoothDevice> availableDevices = new ArrayList<>();
        model.updateAvailableDevicesSpinnerModel(availableDevices);
    }

    public void findAvailableDevices() {

        receiver = new BroadcastReceiver(){
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())){

                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device.getName() != null) {
                        logMessage("mainpain", "name is: " + device);
                        model.addAvailableDevice(device);
                        model.updateAvailableDevicesSpinnerModel(model.getAvailableDevicesList());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        if (model.getBtAdapter() != null && !model.getBtAdapter().isDiscovering()) {
            logMessage("mainpain", "Discovery started successfully");
            model.getBtAdapter().startDiscovery();
        }


    }

    public void connect(){
        Handler handler = new Handler();
        Runnable checkCondition = new Runnable() {
            @Override
            public void run() {
                if (regState == 1) { // Replace with your condition
                    model.getHidDevice().connect(model.getTargetDevice());
                } else {
                    handler.postDelayed(this, 500); // Retry after 500ms
                }
            }
        };

        // Start checking
        handler.post(checkCondition);
    }

    private void saveValues() {
        inputValue = textInputEditText.getText().toString();
        SharedPreferences prefs = getSharedPreferences("BTKBV2", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("input_value" , inputValue);
        editor.apply();

    }


}