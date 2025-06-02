package com.example.btkbv2;

import static android.bluetooth.BluetoothHidDevice.SUBCLASS1_KEYBOARD;



import android.widget.EditText;
import android.app.AlertDialog;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;

import androidx.activity.EdgeToEdge;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Executor;


@SuppressLint("MissingPermission")
public class Controller extends Activity implements UpdateView {

    private BluetoothPermissionManager bluetoothPermissionManager;

    public Spinner pairedDevicesSpinner;
    private ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    public Spinner availableDevicesSpinner;
    private ArrayList<BluetoothDevice> availableDevices = new ArrayList<>();
    private Spinner inputsSpinner;
    private ArrayList<BluetoothDevice> inputs = new ArrayList<>();
    private Spinner passwordsSpinner;
    private ArrayList<BluetoothDevice> passwords = new ArrayList<>();
    private ArrayAdapter<String> inputsAdapter;

    private TextInputEditText textInputEditText;

    private BluetoothDevice targetDevice;
    private BluetoothHidDevice hidDevice;
    private BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    private BroadcastReceiver receiver;

    private ArrayList<byte[]> reportSave;

    private int regState;
    private int currentByte;
    private boolean editMode = false;

    private String inputValue;
    private String actualPassword;
    private boolean isRepeatActive = false;
    private String lastInput = ""; // To store the last input before repeating

    private HashMap<String, String> passwordsMap;
    private HashMap<String, String> inputsMap; // Stores input values without masking
    private boolean isDialogShown = false; // Flag to prevent double popup






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


        SharedPreferences prefs = getSharedPreferences("BTKBV2", MODE_PRIVATE);
        inputValue = prefs.getString("input_value", "");
        textInputEditText = findViewById(R.id.TextInputEditLayout);
        textInputEditText.setText(inputValue);


        getProxy();
        updatePairedDevicesSpinnerModel(pairedDevices);
        updateAvailableDevicesSpinnerModel(availableDevices);
        initializeInputsSpinner();
        initializePasswordSpinner();
        findAvailableDevices();
        spinnerListener();
        buttonListener();
        loadValues();

    }
    @Override
    protected void onResume() {
        super.onResume();
        getProxy();
        Log.d("mainpain", "on Resume");
        if(targetDevice != null && hidDevice != null){
            Log.d("mainpain", "TD: " + targetDevice.getName() + "HID: " + hidDevice);
            connect();
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        saveValues();
        if(hidDevice != null & targetDevice != null) {
            hidDevice.disconnect(targetDevice);
        }
        Log.d("mainpain", "on Pause");
    }
    protected void onDestroy() {
        super.onDestroy();
        saveValues();
        hidDevice.disconnect(targetDevice);
        if (receiver != null) {
            unregisterReceiver(receiver);
            logMessage("mainpain", "Discovery has been stopped");
        }
    }

    private void getProxy() {
        btAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @SuppressLint("MissingPermission")
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
                        @Override
                        public void onConnectionStateChanged(BluetoothDevice device, final int state) {
                            if (device.equals(targetDevice)) {
                                Runnable statusUpdateRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                                    logMessage("mainpain", "HID Device currently disconnected from: " + device.getName());
                                                } else if (state == BluetoothProfile.STATE_CONNECTING) {
                                                    toastMessage("Connecting...");
                                                } else if (state == BluetoothProfile.STATE_CONNECTED) {
                                                    toastMessage("Connected");
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
                            logMessage("mainpain", registered ? "HID Device registered successfully" : "HID Device registration failed");
                            if(registered){
                                //pairedDevicePicked(getPairedDevicesList().indexOf(targetDevice));
                                regState = 1;
                            }
                            else{
                                regState = 0;
                            }
                        }

                        @Override
                        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
                            logMessage("mainpain", "onGetReport: device=" + device + " type=" + type
                                    + " id=" + id + " bufferSize=" + bufferSize);
                        }

                        @Override
                        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] report) {
                            logMessage("mainpain", "onSetReport: device=" + device + " type=" + type
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
    private void registerHidDevice(BluetoothProfile proxy, BluetoothHidDevice.Callback callback){
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
        pairedDevicesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                hidDevice.disconnect(targetDevice);
                Log.e("mainpain", "position: " + position);
                if(position > 0){
                    targetDevice = pairedDevices.get(position - 1);
                }
                else{
                    hidDevice.disconnect(targetDevice);
                }
                if(targetDevice != null && hidDevice != null){
                    connect();
                }
            }


            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        availableDevicesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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
                            toastMessage("Pairing with " + device.getName());
                            updateAvailableDevicesSpinnerModel(availableDevices);
                            updatePairedDevicesSpinnerModel(pairedDevices);
                        } else {
                            toastMessage("Failed to start pairing with " + device.getName());
                        }
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        availableDevicesSpinner.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                updatePairedDevicesSpinnerModel(pairedDevices);
                return false;
            }
        });

        inputsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0 || isDialogShown) return; // Prevent unnecessary triggers

                if (editMode) {
                    isDialogShown = true; // Mark dialog as shown
                    showInputDialog(position);

                } else {
                    // Non-EditMode: Set inputValue to the selected spinner item without the number prefix
                    String selectedItem = parent.getItemAtPosition(position).toString();
                    inputValue = selectedItem.substring(selectedItem.indexOf('.') + 1).trim();
                    textInputEditText.setText(inputValue);
                }

                // Reset selection with a delay to avoid immediate re-trigger
                inputsSpinner.post(() -> {
                    inputsSpinner.setSelection(0);
                    isDialogShown = false; // Reset flag after selection reset
                });
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        passwordsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    // Do nothing if "Passwords" title is selected
                    return;
                }
                inputsSpinner.setSelection(0);

                String key = position + ".";
                actualPassword = passwordsMap.get(key);

                if (editMode) {
                    inputsSpinner.setSelection(0);
                    toastMessage("Edit mode is enabled. Select a different mode to process the password.");
                } else {
                    if (actualPassword != null && !actualPassword.isEmpty()) {
                        // Non-EditMode: Process the actual password but show *
                        textInputEditText.setText("*".repeat(actualPassword.length())); // Masked in the text box
                        Log.d("mainpain", "Selected password: " + actualPassword); // Process actual password
                    } else {
                        // If the slot is empty, clear the text input
                        textInputEditText.setText("");
                        toastMessage("No password set for this slot.");
                    }
                    passwordsSpinner.setSelection(0);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });



    }



    private void buttonListener(){
        findViewById(R.id.inputbutton).setOnClickListener(v -> {
            if (editMode) {
                // EditMode: Update the selected slot with the input value
                String newInputValue = textInputEditText.getText().toString().trim();

                if (inputsSpinner.getSelectedItemPosition() > 0) {
                    // Update the selected input slot in inputsMap
                    String key = inputsSpinner.getSelectedItem().toString().split("\\.")[0] + ".";
                    inputsMap.put(key, newInputValue); // Update inputsMap
                    updateInputsSpinner(); // Refresh inputsSpinner
                    toastMessage("Input slot updated.");
                } else if (passwordsSpinner.getSelectedItemPosition() > 0) {
                    // Update the selected password slot in passwordsMap
                    if (newInputValue.isEmpty()) {
                        toastMessage("Please enter a value to update the password slot.");
                    } else {
                        String key = passwordsSpinner.getSelectedItem().toString().split("\\.")[0] + ".";
                        passwordsMap.put(key, newInputValue);
                        updatePasswordSpinner(); // Refresh passwordsSpinner
                        toastMessage("Password slot updated.");
                    }
                } else {
                    toastMessage("Please select a slot to edit.");
                }
            } else {
                // Non-EditMode: Send the input value or password
                inputValue = textInputEditText.getText().toString();
                if (passwordsSpinner.getSelectedItemPosition() > 0) {
                    if (actualPassword != null && !actualPassword.isEmpty()) {
                        try {
                            convertTextToHidReport(actualPassword);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        Log.d("mainpain", "Password sent: " + actualPassword);

                        // Mask the password in the text box for feedback
                        textInputEditText.setText("*".repeat(actualPassword.length()));
                    } else {
                        toastMessage("No password set for the selected slot.");
                    }
                } else if (!inputValue.isEmpty() && targetDevice != null && hidDevice.getConnectionState(targetDevice) == BluetoothProfile.STATE_CONNECTED) {
                    // Send the input value as a HID report
                    try {
                        convertTextToHidReport(inputValue);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    Log.d("mainpain", "Input value sent: " + inputValue);
                } else if (inputsSpinner.getSelectedItemPosition() > 0) {
                    // Send the selected input spinner value
                    inputValue = inputsSpinner.getSelectedItem().toString();
                    Log.d("mainpain", "Sending input spinner value: " + inputValue);
                } else {
                    toastMessage("Please select an input or password slot.");
                }
            }

            // Reset spinners and clear the text box
            inputsSpinner.setSelection(0);
            passwordsSpinner.setSelection(0);
            textInputEditText.setText("");
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


        Switch editSwitch = findViewById(R.id.edit);
        editSwitch.setOnClickListener(v -> {
            editMode = editSwitch.isChecked();
            logMessage("mainpain", "editMode: " + editMode);
        });
    }

    private void showInputDialog(int pos) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Data for Slot " + pos);

        final EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String newInputValue = input.getText().toString().trim();
            String key = pos + ".";

            setInput(key, newInputValue);

            Log.d("InputDialog", "User entered: " + newInputValue);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void initializeInputsSpinner() {
        inputsSpinner = findViewById(R.id.inputs);

        inputsMap = new HashMap<>();

        // Initialize with 5 empty slots
        for (int i = 1; i <= 5; i++) {
            String key = i + ".";
            inputsMap.put(key, "");  // Empty values
        }

        inputsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, getFormattedInputList());
        inputsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inputsSpinner.setAdapter(inputsAdapter);
    }


    private ArrayList<String> getFormattedInputList() {
        ArrayList<String> items = new ArrayList<>();
        items.add("Inputs"); // Spinner title

        for (int i = 1; i <= 5; i++) {
            String key = i + ".";
            String value = inputsMap.get(key);
            if (value != null && !value.isEmpty()) {
                items.add(i + ". " + value);
            } else {
                items.add(i + ". [Empty slot]");
            }
        }

        return items;
    }

    // Update an entry and refresh the spinner
    private void setInput(String key, String value) {
        if (value.isEmpty()) {
            // If the value is empty, remove the corresponding slot
            inputsMap.remove(key);
            Log.d("InputsSpinner", "Slot " + key + " removed because it is empty.");
        } else {
            // Otherwise, update the slot with the new value
            inputsMap.put(key, value);
        }

        // Refresh the spinner
        updateInputsSpinner();
    }


    // Refresh the spinner from the current map
    private void updateInputsSpinner() {
        // We directly update the spinner adapter based on the current state of inputsMap
        if (inputsAdapter != null) {
            ArrayList<String> formattedList = getFormattedInputList();  // Create the updated list based on inputsMap
            inputsAdapter.clear();  // Clear the previous spinner items
            inputsAdapter.addAll(formattedList);  // Add the updated list to the adapter
            inputsAdapter.notifyDataSetChanged();  // Notify the adapter that the data has changed
        } else {
            Log.e("InputsSpinner", "Adapter is not initialized.");
        }
    }





    private void initializePasswordSpinner() {
        passwordsSpinner = findViewById(R.id.passwords);

        // Initialize the password map
        passwordsMap = new HashMap<>();

        // Populate spinner with "Passwords" and empty slots
        ArrayList<String> passwordOptions = new ArrayList<>();
        passwordOptions.add("Passwords"); // First option
        for (int i = 1; i <= 5; i++) {
            passwordsMap.put(i + ".", ""); // Store empty value initially
            passwordOptions.add(i + "."); // Add empty slot to spinner
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, passwordOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        passwordsSpinner.setAdapter(adapter);
    }
    private void updatePasswordSpinner() {
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) passwordsSpinner.getAdapter();
        ArrayList<String> items = new ArrayList<>();

        items.add("Passwords"); // Add the default title
        for (int i = 1; i <= passwordsMap.size(); i++) {
            String key = i + ".";
            String password = passwordsMap.get(key);
            String maskedPassword = password.isEmpty() ? "" : "*".repeat(password.length());
            items.add(key + (maskedPassword.isEmpty() ? "" : " " + maskedPassword));
        }

        // Rebuild the spinner with updated values
        adapter.clear();
        adapter.addAll(items);
        adapter.notifyDataSetChanged();
    }

    private void loadValues() {
        SharedPreferences prefs = getSharedPreferences("BTKBV2", MODE_PRIVATE);

        // Initialize 5 input slots in inputsMap
        inputsMap = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            String key = i + ".";
            String savedInput = prefs.getString("input_" + key, ""); // Load saved or fallback to empty
            inputsMap.put(key, savedInput);
        }

        // Initialize password map only if it's not already (just in case)
        if (passwordsMap == null) {
            passwordsMap = new HashMap<>();
        }

        // Predefine your password slots (if you have specific keys, do that here)
        for (int i = 1; i <= 5; i++) { // You can increase the range if needed
            String key = i + ".";
            String savedPassword = prefs.getString("password_" + key, "");
            passwordsMap.put(key, savedPassword);
        }

        updateInputsSpinner();
        updatePasswordSpinner();

        Log.d("mainpain", "Passwords and inputs loaded from SharedPreferences.");
    }










    private void updateSpinnerWithInputValue(Spinner spinner, int position) {
        if (position > 0) { // Skip the first "Passwords" option
            ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinner.getAdapter();
            ArrayList<String> items = new ArrayList<>();
            for (int i = 0; i < adapter.getCount(); i++) {
                items.add(adapter.getItem(i));
            }

            // Retain the prefix (e.g., "1.") and conditionally mask the value
            String selectedKey = items.get(position).split("\\.")[0] + ".";
            passwordsMap.put(selectedKey, inputValue); // Update the actual value in the map
            String maskedPassword = inputValue.isEmpty() ? "" : "*".repeat(inputValue.length()); // Masked value only if not empty
            String updatedItem = selectedKey + (maskedPassword.isEmpty() ? "" : " " + maskedPassword); // Combine key with masked value

            items.set(position, updatedItem); // Update the selected item

            // Rebuild the spinner with updated values
            ArrayAdapter<String> newAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
            newAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(newAdapter);

            // Retain the current selection
            spinner.setSelection(position);
        }
    }
    private void updatePairedDevicesSpinnerModel(ArrayList<BluetoothDevice> newPairedDevices){
        Set<BluetoothDevice> pairedDevicesSet = btAdapter.getBondedDevices();
        pairedDevices.clear();
        pairedDevices.addAll(pairedDevicesSet);
        pairedDevicesSpinner = ((Activity) this).findViewById(R.id.devices);

        int currentSelection = pairedDevicesSpinner.getSelectedItemPosition();

        pairedDevices = newPairedDevices;


        for(BluetoothDevice i: pairedDevices){
            if(availableDevices.contains(i)){
                availableDevices.remove(i);
            }
        }
        updateAvailableDevicesSpinnerModel(availableDevices);

        ArrayList<String> names = new ArrayList<>();
        names.add("Paired Devices");
        for(BluetoothDevice i: pairedDevices){
            names.add(i.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pairedDevicesSpinner.setAdapter(adapter);

        if (currentSelection >= 0 && currentSelection < adapter.getCount()) {
            pairedDevicesSpinner.setSelection(currentSelection);
        }
    }
    private void updateAvailableDevicesSpinnerModel(ArrayList<BluetoothDevice> newAvailableDevices) {

        availableDevicesSpinner = ((Activity) this).findViewById(R.id.paireddevices);

        int currentSelection = availableDevicesSpinner.getSelectedItemPosition();

        availableDevices = newAvailableDevices;

        ArrayList<String> names = new ArrayList<>();
        names.add("Available Devices");
        for(BluetoothDevice i: availableDevices){
            names.add(i.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        availableDevicesSpinner.setAdapter(adapter);

        if (currentSelection >= 0 && currentSelection < adapter.getCount()) {
            availableDevicesSpinner.setSelection(currentSelection);
        }
    }
    private void findAvailableDevices() {

        receiver = new BroadcastReceiver(){
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())){

                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device.getName() != null) {
                        if(!availableDevices.contains(device) && !pairedDevices.contains(device)){
                            availableDevices.add(device);
                        }
                        updateAvailableDevicesSpinnerModel(availableDevices);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        if (btAdapter != null && !btAdapter.isDiscovering()) {
            logMessage("mainpain", "Discovery started successfully");
            btAdapter.startDiscovery();
        }


    }

    private void connect(){
        Handler handler = new Handler();
        Runnable checkCondition = new Runnable() {
            @Override
            public void run() {
                if (regState == 1) { // Replace with your condition
                    hidDevice.connect(targetDevice);
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

        // Save inputValue
        editor.putString("input_value", inputValue);

        // Save passwordsMap
        for (String key : passwordsMap.keySet()) {
            editor.putString("password_" + key, passwordsMap.get(key)); // Save each password slot
        }

        // Save inputsMap
        for (String key : inputsMap.keySet()) {
            editor.putString("input_" + key, inputsMap.get(key)); // Save each input slot
        }

        editor.apply();
        Log.d("mainpain", "Values, passwords, and inputs saved to SharedPreferences.");
    }




    private byte[] getDescriptor(){
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

    private void convertTextToHidReport(String text) throws InterruptedException {
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
    private void sendReport(ArrayList<byte[]> report, int start) {
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

    private void toastMessage(String message){
        Log.d("mainpain", message);
        ((Activity) this).runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }
}