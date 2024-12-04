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
import android.os.Looper;
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
import java.util.concurrent.Executor;


@SuppressLint("MissingPermission")
public class Controller extends Activity implements UpdateView {

    private BluetoothPermissionManager bluetoothPermissionManager;
    private UpdateView UView;

    public Spinner pairedDevicesSpinner;
    private ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    public Spinner availableDevicesSpinner;
    private ArrayList<BluetoothDevice> availableDevices = new ArrayList<>();
    private Spinner inputs;
    private Spinner passwords;

    private TextInputEditText textInputEditText;

    private BluetoothDevice targetDevice;
    private BluetoothHidDevice hidDevice;
    private BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    private BroadcastReceiver receiver;

    private ArrayList<byte[]> reportSave;
    private int currentByte;

    private int regState;

    private String inputValue;
    private boolean isRepeatActive = false;
    private String lastInput = ""; // To store the last input before repeating





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
            if(getTargetDevice() != null &&  getHidDevice().getConnectionState(getTargetDevice()) == BluetoothProfile.STATE_CONNECTED){
                try {
                    convertTextToHidReport(inputValue);
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
        if(getTargetDevice() != null && getHidDevice() != null){
            Log.d("mainpain", "TD: " + getTargetDevice().getName() + "HID: " + getHidDevice());
            connect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveValues();
        if(getHidDevice() != null & getTargetDevice() != null) {
            getHidDevice().disconnect(getTargetDevice());
        }
        Log.d("mainpain", "on Pause");
    }

    protected void onDestroy() {
        super.onDestroy();
        saveValues();
        getHidDevice().disconnect(getTargetDevice());
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    private void getProxy() {
        getBtAdapter().getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @SuppressLint("MissingPermission")
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
                        @Override
                        public void onConnectionStateChanged(BluetoothDevice device, final int state) {
                            if (device.equals(getTargetDevice())) {
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
                                //pairedDevicePicked(getPairedDevicesList().indexOf(getTargetDevice()));
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
                    registerHidDevice(proxy, callback);
                }
            }


            @Override
            public void onServiceDisconnected(int profile) {
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    public void registerHidDevice(BluetoothProfile proxy, BluetoothHidDevice.Callback callback){
        hidDevice = (BluetoothHidDevice) proxy;

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "BlueHID",
                "Android HID hackery",
                "Android",
                SUBCLASS1_KEYBOARD,
                getDescriptor()
        );
        Executor executor = runnable -> new Thread(runnable).start();

        hidDevice.registerApp(sdp, null, null, executor, callback);
    }

    private void spinnerListener() {
        getPairedDevicesSpinner().setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                pairedDevicePicked(position);
                if(getTargetDevice() != null && getHidDevice() != null){
                    Log.d("mainpain", "testing");
                    connect();
                }
            }


            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        getAvailableDevicesSpinner().setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                availableDevicePicked(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        getAvailableDevicesSpinner().setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                findAvailableDevices();
                return false;
            }
        });



    }

    private void initializePairedSpinner() {
        Set<BluetoothDevice> pairedDevicesSet = getBtAdapter().getBondedDevices();
        ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();

        pairedDevices.addAll(pairedDevicesSet);

        updatePairedDevicesSpinnerModel(pairedDevices);

    }

    private void initializeAvailableSpinner() {
        ArrayList<BluetoothDevice> availableDevices = new ArrayList<>();
        updateAvailableDevicesSpinnerModel(availableDevices);
    }

    public void findAvailableDevices() {

        receiver = new BroadcastReceiver(){
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())){

                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device.getName() != null) {
                        logMessage("mainpain", "name is: " + device);
                        addAvailableDevice(device);
                        updateAvailableDevicesSpinnerModel(getAvailableDevicesList());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        if (getBtAdapter() != null && !getBtAdapter().isDiscovering()) {
            logMessage("mainpain", "Discovery started successfully");
            getBtAdapter().startDiscovery();
        }


    }

    public void connect(){
        Handler handler = new Handler();
        Runnable checkCondition = new Runnable() {
            @Override
            public void run() {
                if (regState == 1) { // Replace with your condition
                    getHidDevice().connect(getTargetDevice());
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

    public byte[] getDescriptor(){
        byte[] descriptor = new byte[]{
                (byte) 0x05, (byte) 0x01,           // Usage Page (Generic Desktop)
                (byte) 0x09, (byte) 0x06,           // Usage (Keyboard)
                (byte) 0xA1, (byte) 0x01,           // Collection (Application)
                (byte) 0x05, (byte) 0x07,           // Usage Page (Key Codes)
                (byte) 0x19, (byte) 0xE0,           // Usage Minimum (224)
                (byte) 0x29, (byte) 0xE7,           // Usage Maximum (231)
                (byte) 0x15, (byte) 0x00,           // Logical Minimum (0)
                (byte) 0x25, (byte) 0x01,           // Logical Maximum (1)
                (byte) 0x75, (byte) 0x01,           // Report Size (1)
                (byte) 0x95, (byte) 0x08,           // Report Count (8)
                (byte) 0x81, (byte) 0x02,           // Input (Data, Variable, Absolute)

                (byte) 0x95, (byte) 0x01,           // Report Count (1)
                (byte) 0x75, (byte) 0x08,           // Report Size (8)
                (byte) 0x81, (byte) 0x01,           // Input (Constant), reserved byte(1)

                (byte) 0x95, (byte) 0x05,           // Report Count (5)
                (byte) 0x75, (byte) 0x01,           // Report Size (1)
                (byte) 0x05, (byte) 0x08,           // Usage Page (LEDs)
                (byte) 0x19, (byte) 0x01,           // Usage Minimum (1)
                (byte) 0x29, (byte) 0x05,           // Usage Maximum (5)
                (byte) 0x91, (byte) 0x02,           // Output (Data, Variable, Absolute), LED report
                (byte) 0x95, (byte) 0x01,           // Report Count (1)
                (byte) 0x75, (byte) 0x03,           // Report Size (3)
                (byte) 0x91, (byte) 0x01,           // Output (Constant), LED report padding

                (byte) 0x95, (byte) 0x06,           // Report Count (6)
                (byte) 0x75, (byte) 0x08,           // Report Size (8)
                (byte) 0x15, (byte) 0x00,           // Logical Minimum (0)
                (byte) 0x25, (byte) 0x65,           // Logical Maximum (101)
                (byte) 0x05, (byte) 0x07,           // Usage Page (Key Codes)
                (byte) 0x19, (byte) 0x00,           // Usage Minimum (0)
                (byte) 0x29, (byte) 0x65,           // Usage Maximum (101)
                (byte) 0x81, (byte) 0x00,           // Input (Data, Array), Key array (6 bytes)

                (byte) 0x09, (byte) 0x05,           // Usage (Vendor Defined)
                (byte) 0x15, (byte) 0x00,           // Logical Minimum (0)
                (byte) 0x26, (byte) 0xFF, (byte) 0x00, // Logical Maximum (255)
                (byte) 0x75, (byte) 0x08,           // Report Size (8)
                (byte) 0x95, (byte) 0x02,           // Report Count (2)
                (byte) 0xB1, (byte) 0x02,           // Feature (Data, Variable, Absolute)

                (byte) 0xC0                          // End Collection (Application)
        };
        return descriptor;
    }

    public void updatePairedDevicesSpinnerModel(ArrayList<BluetoothDevice> newPairedDevices){
        pairedDevicesSpinner = ((Activity) this).findViewById(R.id.devices);

        int currentSelection = pairedDevicesSpinner.getSelectedItemPosition();

        pairedDevices = newPairedDevices;


        for(BluetoothDevice i: pairedDevices){
            if(availableDevices.contains(i)){
                availableDevices.remove(i);
            }
        }
        updateAvailableDevicesSpinnerModel(availableDevices);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, getNameList(pairedDevices,0));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pairedDevicesSpinner.setAdapter(adapter);

        if (currentSelection >= 0 && currentSelection < adapter.getCount()) {
            pairedDevicesSpinner.setSelection(currentSelection);
        }
    }
    public void pairedDevicePicked(int position){
        Log.e("mainpain", "position: " + position);
        if(position > 0){
            targetDevice = pairedDevices.get(position - 1);
        }
        else{
            hidDevice.disconnect(targetDevice);
        }
    }
    public void updateAvailableDevicesSpinnerModel(ArrayList<BluetoothDevice> newAvailableDevices) {
        availableDevicesSpinner = ((Activity) this).findViewById(R.id.paireddevices);

        int currentSelection = availableDevicesSpinner.getSelectedItemPosition();

        availableDevices = newAvailableDevices;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, getNameList(availableDevices,1));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        availableDevicesSpinner.setAdapter(adapter);

        if (currentSelection >= 0 && currentSelection < adapter.getCount()) {
            availableDevicesSpinner.setSelection(currentSelection);
        }
    }
    public void availableDevicePicked(int position){
        if(position <= 0){
            return;
        }
        BluetoothDevice device = availableDevices.get(position-1); // Get the selected device
        Log.d("mainpain", "Pairing with " + device.getName());
        if (device != null) {
            if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                // The device is not bonded, initiate pairing
                boolean startedPairing = device.createBond();
                if (startedPairing) {
                    UView.toastMessage(this,"Pairing with " + device.getName());
                } else {
                    UView.toastMessage(this,"Failed to start pairing with " + device.getName());
                }
            }


        }

    }
    public Spinner getPairedDevicesSpinner(){
        return pairedDevicesSpinner;
    }
    public ArrayList<BluetoothDevice> getPairedDevicesList(){
        return pairedDevices;
    }
    public Spinner getAvailableDevicesSpinner(){
        return availableDevicesSpinner;
    }
    public void addAvailableDevice(BluetoothDevice newavailableDevice){
        if(!availableDevices.contains(newavailableDevice) && !pairedDevices.contains(newavailableDevice)){
            availableDevices.add(newavailableDevice);
        }
    }
    public ArrayList<BluetoothDevice> getAvailableDevicesList(){
        return availableDevices;
    }
    public BluetoothAdapter getBtAdapter(){
        return btAdapter;
    }
    public BluetoothDevice getTargetDevice(){
        return targetDevice;
    }
    public BluetoothHidDevice getHidDevice(){
        return hidDevice;
    }
    public int getCurrentByte(){
        return currentByte;
    }
    public ArrayList<byte[]> getReportSave(){
        return reportSave;
    }
    private ArrayList<String> getNameList(ArrayList<BluetoothDevice> BluetoothDevices, int type){
        ArrayList<String> names = new ArrayList<>();
        if(type == 1){
            names.add("Available Devices");
            for(BluetoothDevice i: BluetoothDevices){
                names.add(i.getName());
            }
        }
        else{
            names.add("Paired Devices");
            for(BluetoothDevice i: BluetoothDevices){
                names.add(i.getName());
            }
        }
        return names;
    }
    public void convertTextToHidReport(String text) throws InterruptedException {
        ArrayList<byte[]> reportMessage = new ArrayList<>();
        currentByte = 0;
        byte[] report = new byte[8];

        // HID report size for a keyboard is usually 8 bytes
        for (int i = 0; i < text.length(); i++) {
            report = new byte[8];
            char character = text.charAt(i);
            byte keyCode = getKeyCode(character);


            if (Character.isUpperCase(character) || "~!@#$%^&*()_+{}|:\"<>?".indexOf(character) >= 0) {
                // Set the left Shift modifier key (bit 1 in the first byte of the report)
                report[0] = 0x02;  // 0x02 represents the left Shift key
                // Convert the character to lowercase if it was an uppercase letter
                if (Character.isUpperCase(character)) {
                    character = Character.toLowerCase(character);
                    keyCode = getKeyCode(character);
                }
            }

            report[2] = keyCode;
            reportMessage.add(report);
        }

        sendReport(reportMessage,0);
    }
    public void sendReport(ArrayList<byte[]> report, int start) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                reportSave = report;
                int state = hidDevice.getConnectionState(targetDevice);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    byte[] report2 = new byte[8];
                    report2[2] = 0x00;

                    for (int i = start; i < report.size(); i++) {
                        int finalI = i;
                        handler.postDelayed(() -> {
                            hidDevice.sendReport(targetDevice, SUBCLASS1_KEYBOARD, report.get(finalI));
                            hidDevice.sendReport(targetDevice, SUBCLASS1_KEYBOARD, report2);
                            currentByte += 1;
                        }, i * 40); // 40ms delay between keystrokes
                    }

                    // Final cleanup after all keystrokes
                    handler.postDelayed(() -> {
                        hidDevice.sendReport(targetDevice, SUBCLASS1_KEYBOARD, report2);
                        reportSave.clear();
                        currentByte = 0;
                    }, report.size() * 40);
                } else {
                    Log.d("Bluetooth", "Device is not in a connected state.");
                }
            } catch (Exception e) {
                Log.e("Bluetooth", "Error: " + e.getMessage());
            }
        });
    }
    private byte getKeyCode(char character) {
        switch (character) {
            // Lowercase letters
            case 'a':
                return 0x04;
            case 'b':
                return 0x05;
            case 'c':
                return 0x06;
            case 'd':
                return 0x07;
            case 'e':
                return 0x08;
            case 'f':
                return 0x09;
            case 'g':
                return 0x0A;
            case 'h':
                return 0x0B;
            case 'i':
                return 0x0C;
            case 'j':
                return 0x0D;
            case 'k':
                return 0x0E;
            case 'l':
                return 0x0F;
            case 'm':
                return 0x10;
            case 'n':
                return 0x11;
            case 'o':
                return 0x12;
            case 'p':
                return 0x13;
            case 'q':
                return 0x14;
            case 'r':
                return 0x15;
            case 's':
                return 0x16;
            case 't':
                return 0x17;
            case 'u':
                return 0x18;
            case 'v':
                return 0x19;
            case 'w':
                return 0x1A;
            case 'x':
                return 0x1B;
            case 'y':
                return 0x1C;
            case 'z':
                return 0x1D;

            // Uppercase letters (Shift modifier required)
            case 'A':
                return 0x04;
            case 'B':
                return 0x05;
            case 'C':
                return 0x06;
            case 'D':
                return 0x07;
            case 'E':
                return 0x08;
            case 'F':
                return 0x09;
            case 'G':
                return 0x0A;
            case 'H':
                return 0x0B;
            case 'I':
                return 0x0C;
            case 'J':
                return 0x0D;
            case 'K':
                return 0x0E;
            case 'L':
                return 0x0F;
            case 'M':
                return 0x10;
            case 'N':
                return 0x11;
            case 'O':
                return 0x12;
            case 'P':
                return 0x13;
            case 'Q':
                return 0x14;
            case 'R':
                return 0x15;
            case 'S':
                return 0x16;
            case 'T':
                return 0x17;
            case 'U':
                return 0x18;
            case 'V':
                return 0x19;
            case 'W':
                return 0x1A;
            case 'X':
                return 0x1B;
            case 'Y':
                return 0x1C;
            case 'Z':
                return 0x1D;

            // Numbers
            case '1':
                return 0x1E;
            case '2':
                return 0x1F;
            case '3':
                return 0x20;
            case '4':
                return 0x21;
            case '5':
                return 0x22;
            case '6':
                return 0x23;
            case '7':
                return 0x24;
            case '8':
                return 0x25;
            case '9':
                return 0x26;
            case '0':
                return 0x27;

            // Special characters
            case '\n':
                return 0x28; // Enter
            case ' ':
                return 0x2C;  // Spacebar
            case '-':
                return 0x2D;  // Hyphen
            case '=':
                return 0x2E;  // Equal sign
            case '[':
                return 0x2F;  // Open square bracket
            case ']':
                return 0x30;  // Close square bracket
            case '\\':
                return 0x31; // Backslash
            case ';':
                return 0x33;  // Semicolon
            case '\'':
                return 0x34; // Apostrophe
            case '`':
                return 0x35;  // Grave accent
            case ',':
                return 0x36;  // Comma
            case '.':
                return 0x37;  // Period
            case '/':
                return 0x38;  // Slash

            // Shift-modified symbols
            case '!':
                return 0x1E; // Shift + 1
            case '@':
                return 0x1F; // Shift + 2
            case '#':
                return 0x20; // Shift + 3
            case '$':
                return 0x21; // Shift + 4
            case '%':
                return 0x22; // Shift + 5
            case '^':
                return 0x23; // Shift + 6
            case '&':
                return 0x24; // Shift + 7
            case '*':
                return 0x25; // Shift + 8
            case '(':
                return 0x26; // Shift + 9
            case ')':
                return 0x27; // Shift + 0
            case '_':
                return 0x2D; // Shift + Hyphen
            case '+':
                return 0x2E; // Shift + Equal sign
            case '{':
                return 0x2F; // Shift + Open square bracket
            case '}':
                return 0x30; // Shift + Close square bracket
            case '|':
                return 0x31; // Shift + Backslash
            case ':':
                return 0x33; // Shift + Semicolon
            case '"':
                return 0x34; // Shift + Apostrophe
            case '~':
                return 0x35; // Shift + Grave accent
            case '<':
                return 0x36; // Shift + Comma
            case '>':
                return 0x37; // Shift + Period
            case '?':
                return 0x38; // Shift + Slash

            default:
                return 0; // Return 0 for unhandled characters
        }
    }

}