package com.thelazyj.recordwithmusic;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.thelazyj.recordwithsound.R;

public class SettingActivity extends BottomSheetDialogFragment{

    private EditText bitrateEditText;
    private MainActivityCallback callback;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Create the view of the pop-up
        View view = inflater.inflate(R.layout.activity_setting, container, false);

        // Check for Bitrate choice
        RadioButton bitrateLow = (RadioButton) view.findViewById(R.id.bitrate_low);
        RadioButton bitrateMedium = (RadioButton) view.findViewById(R.id.bitrate_medium);
        RadioButton bitrateHigh = (RadioButton) view.findViewById(R.id.bitrate_high);
        refreshBitrateCheck(bitrateLow, bitrateMedium, bitrateHigh);

        //Switch button for save file
        /**Switch mSwitchFolder = view.findViewById(R.id.switch_folder);
        mSwitchFolder.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    // Switch is ON
                    MainActivity.saveDirectory = "Gallery";
                } else {
                    // Switch is OFF
                    MainActivity.saveDirectory = "Record With Music";
                }
            }
        });**/
        // Update Bitrate Correction Factor from settings menu
        /**bitrateEditText = (EditText) view.findViewById(R.id.bitrate_value);
        bitrateEditText.setText(String.valueOf(MainActivity.BITRATE_CORRECTION_FACTOR));
        bitrateEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if(!s.toString().isEmpty()) {
                    float temp = Float.parseFloat(s.toString());
                    if (temp > 0 && temp <= 1) {
                        MainActivity.BITRATE_CORRECTION_FACTOR = temp;
                        refreshBitrateCheck(bitrateLow, bitrateMedium, bitrateHigh);
                    } else {
                        Toast.makeText(getActivity().getApplicationContext(), "Number need to be between 0.01 and 1", Toast.LENGTH_SHORT).show();
                    }
                    updatePrefs();
                }
            }
        });**/

        RadioGroup bitrateRatioGroup = (RadioGroup) view.findViewById(R.id.bitrate_group);
        bitrateRatioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.bitrate_low:
                    MainActivity.BITRATE_CORRECTION_FACTOR = 0.03F;
                    //bitrateEditText.setText(String.valueOf(MainActivity.BITRATE_CORRECTION_FACTOR));
                    updatePrefs();
                    break;
                case R.id.bitrate_medium:
                    MainActivity.BITRATE_CORRECTION_FACTOR = 0.10F;
                    //bitrateEditText.setText(String.valueOf(MainActivity.BITRATE_CORRECTION_FACTOR));
                    updatePrefs();
                    break;
                case R.id.bitrate_high:
                    MainActivity.BITRATE_CORRECTION_FACTOR = 0.18F;
                    //bitrateEditText.setText(String.valueOf(MainActivity.BITRATE_CORRECTION_FACTOR));
                    updatePrefs();
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + checkedId);
            }
            updatePrefs();
        });

        /* Check for Framerate, first make sure the good FPS is checked, and after change var depending of which is clicked
        frameRateRB1 = (RadioButton) view.findViewById(R.id.frame_rate_30);
        frameRateRB2 = (RadioButton) view.findViewById(R.id.frame_rate_60);
        if (MainActivity.FRAME_RATE == 30) {
            frameRateRB1.setChecked(true);
        } else {
            frameRateRB2.setChecked(true);
        }
        frameRateGroup = (RadioGroup) view.findViewById(R.id.frame_rate_group);
        frameRateGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.frame_rate_30:
                        MainActivity.FRAME_RATE = 30;
                        break;
                    case R.id.frame_rate_60:
                        MainActivity.FRAME_RATE = 60;
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + checkedId);
                }
                updatePrefs();
            }
        });*/

        // Check maximum number of camera
        Spinner numberCamera = (Spinner) view.findViewById(R.id.camera_limit_spinner);
        numberCamera.setSelection(MainActivity.CAMERA_LIMITS -1); // -1 as list start at 0
        numberCamera.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                MainActivity.CAMERA_LIMITS = i + 1;
                updatePrefs();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });



        return view;
    }


    // 3 next function is to be use with callback between MainActivity and SettingActivity
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        callback = (MainActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        callback = null;
    }

    private void updatePrefs() {
        if (callback != null) {
            callback.updatePrefs();
        }
    }

    // Refresh quality bitrate
    private void refreshBitrateCheck(RadioButton bitrateLow, RadioButton bitrateMedium, RadioButton bitrateHigh) {
        if (MainActivity.BITRATE_CORRECTION_FACTOR == 0.03F) {
            bitrateLow.setChecked(true);
        } else if (MainActivity.BITRATE_CORRECTION_FACTOR == 0.10F){
            bitrateMedium.setChecked(true);
        } else if (MainActivity.BITRATE_CORRECTION_FACTOR == 0.18F){
            bitrateHigh.setChecked(true);
        } else {
            bitrateLow.setChecked(false);
            bitrateMedium.setChecked(false);
            bitrateHigh.setChecked(false);
        }
    }

}
