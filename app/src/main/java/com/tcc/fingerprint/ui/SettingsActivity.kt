package com.tcc.fingerprint.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R
import com.tcc.fingerprint.data.SharedPreferencesManager
import com.tcc.fingerprint.utils.Constants

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var prefsManager: SharedPreferencesManager
    
    private lateinit var apiUrlEdit: EditText
    private lateinit var apiTimeoutEdit: EditText
    private lateinit var maxRetriesEdit: EditText
    private lateinit var confidenceThresholdSeekBar: SeekBar
    private lateinit var confidenceThresholdText: TextView
    private lateinit var stabilityThresholdSeekBar: SeekBar
    private lateinit var stabilityThresholdText: TextView
    private lateinit var qualityThresholdSeekBar: SeekBar
    private lateinit var qualityThresholdText: TextView
    private lateinit var useTorchSwitch: Switch
    private lateinit var autoFocusSwitch: Switch
    private lateinit var debugModeSwitch: Switch
    private lateinit var saveDebugImagesSwitch: Switch
    private lateinit var resetButton: Button
    private lateinit var saveButton: Button
    private lateinit var backButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        prefsManager = SharedPreferencesManager(this)
        
        initializeViews()
        loadCurrentSettings()
        setupListeners()
    }
    
    private fun initializeViews() {
        apiUrlEdit = findViewById(R.id.apiUrlEdit)
        apiTimeoutEdit = findViewById(R.id.apiTimeoutEdit)
        maxRetriesEdit = findViewById(R.id.maxRetriesEdit)
        confidenceThresholdSeekBar = findViewById(R.id.confidenceThresholdSeekBar)
        confidenceThresholdText = findViewById(R.id.confidenceThresholdText)
        stabilityThresholdSeekBar = findViewById(R.id.stabilityThresholdSeekBar)
        stabilityThresholdText = findViewById(R.id.stabilityThresholdText)
        qualityThresholdSeekBar = findViewById(R.id.qualityThresholdSeekBar)
        qualityThresholdText = findViewById(R.id.qualityThresholdText)
        useTorchSwitch = findViewById(R.id.useTorchSwitch)
        autoFocusSwitch = findViewById(R.id.autoFocusSwitch)
        debugModeSwitch = findViewById(R.id.debugModeSwitch)
        saveDebugImagesSwitch = findViewById(R.id.saveDebugImagesSwitch)
        resetButton = findViewById(R.id.resetButton)
        saveButton = findViewById(R.id.saveButton)
        backButton = findViewById(R.id.backButton)
    }
    
    private fun loadCurrentSettings() {
        // API Settings
        apiUrlEdit.setText(prefsManager.apiBaseUrl)
        apiTimeoutEdit.setText(prefsManager.apiTimeout.toString())
        maxRetriesEdit.setText(prefsManager.maxRetries.toString())
        
        // Detection Settings
        val confidenceThreshold = (prefsManager.confidenceThreshold * 100).toInt()
        confidenceThresholdSeekBar.progress = confidenceThreshold
        confidenceThresholdText.text = "Confidence Threshold: ${confidenceThreshold}%"
        
        val stabilityThreshold = (prefsManager.stabilityThreshold * 100).toInt()
        stabilityThresholdSeekBar.progress = stabilityThreshold
        stabilityThresholdText.text = "Stability Threshold: ${stabilityThreshold}%"
        
        val qualityThreshold = (prefsManager.qualityThreshold * 100).toInt()
        qualityThresholdSeekBar.progress = qualityThreshold
        qualityThresholdText.text = "Quality Threshold: ${qualityThreshold}%"
        
        // Camera Settings
        useTorchSwitch.isChecked = prefsManager.useTorch
        autoFocusSwitch.isChecked = prefsManager.autoFocus
        
        // Debug Settings
        debugModeSwitch.isChecked = prefsManager.debugMode
        saveDebugImagesSwitch.isChecked = prefsManager.saveDebugImages
    }
    
    private fun setupListeners() {
        confidenceThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                confidenceThresholdText.text = "Confidence Threshold: ${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        stabilityThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                stabilityThresholdText.text = "Stability Threshold: ${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        qualityThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityThresholdText.text = "Quality Threshold: ${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        saveButton.setOnClickListener {
            saveSettings()
        }
        
        resetButton.setOnClickListener {
            resetToDefaults()
        }
        
        backButton.setOnClickListener {
            finish()
        }
    }
    
    private fun saveSettings() {
        try {
            // API Settings
            prefsManager.apiBaseUrl = apiUrlEdit.text.toString()
            prefsManager.apiTimeout = apiTimeoutEdit.text.toString().toLong()
            prefsManager.maxRetries = maxRetriesEdit.text.toString().toInt()
            
            // Detection Settings
            prefsManager.confidenceThreshold = confidenceThresholdSeekBar.progress / 100f
            prefsManager.stabilityThreshold = stabilityThresholdSeekBar.progress / 100f
            prefsManager.qualityThreshold = qualityThresholdSeekBar.progress / 100f
            
            // Camera Settings
            prefsManager.useTorch = useTorchSwitch.isChecked
            prefsManager.autoFocus = autoFocusSwitch.isChecked
            
            // Debug Settings
            prefsManager.debugMode = debugModeSwitch.isChecked
            prefsManager.saveDebugImages = saveDebugImagesSwitch.isChecked
            
            android.widget.Toast.makeText(this, "Settings saved successfully", android.widget.Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error saving settings: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    private fun resetToDefaults() {
        prefsManager.resetToDefaults()
        loadCurrentSettings()
        android.widget.Toast.makeText(this, "Settings reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
    }
} 