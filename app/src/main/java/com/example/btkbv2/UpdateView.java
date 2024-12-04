package com.example.btkbv2;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public interface UpdateView {


    default void updatePairedDevicesSpinnerView(Context context, ArrayList<String> names){
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }
    default void updateAvailableDevicesSpinnerView(Context context, List<String> names, Spinner availableDevicesSpinner){
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, names);
        availableDevicesSpinner.setAdapter(adapter);
    }




    default void toastMessage(Context context, String message){
        Log.d("mainpain", message);
        ((Activity) context).runOnUiThread(() -> {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        });
    }

    default void logMessage(String tag, String message) {
        Log.d(tag, message);
    }
}
