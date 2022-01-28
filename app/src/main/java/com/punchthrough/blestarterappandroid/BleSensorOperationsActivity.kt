/*
 * Copyright 2019 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.isIndicatable
import com.punchthrough.blestarterappandroid.ble.isNotifiable
import com.punchthrough.blestarterappandroid.ble.isReadable
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import com.punchthrough.blestarterappandroid.ble.toHexString
import kotlinx.android.synthetic.main.activity_ble_operations.log_scroll_view
import kotlinx.android.synthetic.main.activity_ble_operations.log_text_view
import kotlinx.android.synthetic.main.sensor_control.battery
import kotlinx.android.synthetic.main.sensor_control.batteryLevel
import kotlinx.android.synthetic.main.sensor_control.data
import kotlinx.android.synthetic.main.sensor_control.flashSize
import kotlinx.android.synthetic.main.sensor_control.flashUsage
import kotlinx.android.synthetic.main.sensor_control.getFlashSize
import kotlinx.android.synthetic.main.sensor_control.getFlashUsage
import kotlinx.android.synthetic.main.sensor_control.getLog
import kotlinx.android.synthetic.main.sensor_control.humidity
import kotlinx.android.synthetic.main.sensor_control.interval
import kotlinx.android.synthetic.main.sensor_control.intervalText
import kotlinx.android.synthetic.main.sensor_control.loggerRefTime
import kotlinx.android.synthetic.main.sensor_control.temperature
import kotlinx.android.synthetic.main.sensor_control.time
import org.jetbrains.anko.alert
import org.jetbrains.anko.collections.forEachByIndex
import org.jetbrains.anko.noButton
import org.jetbrains.anko.selector
import org.jetbrains.anko.yesButton
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


class BleSensorOperationsActivity : AppCompatActivity() {

    private lateinit var device: BluetoothDevice
    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }
    private val characteristicProperties by lazy {
        characteristics.map { characteristic ->
            characteristic to mutableListOf<CharacteristicProperty>().apply {
                if (characteristic.isNotifiable()) add(CharacteristicProperty.Notifiable)
                if (characteristic.isIndicatable()) add(CharacteristicProperty.Indicatable)
                if (characteristic.isReadable()) add(CharacteristicProperty.Readable)
                if (characteristic.isWritable()) add(CharacteristicProperty.Writable)
                if (characteristic.isWritableWithoutResponse()) {
                    add(CharacteristicProperty.WritableWithoutResponse)
                }
            }.toList()
        }.toMap()
    }
    private val characteristicAdapter: CharacteristicAdapter by lazy {
        CharacteristicAdapter(characteristics) { characteristic ->
            showCharacteristicOptions(characteristic)
        }
    }
    private var notifyingCharacteristics = mutableListOf<UUID>()


    override fun onCreate(savedInstanceState: Bundle?) {
        ConnectionManager.registerListener(connectionEventListener)
        super.onCreate(savedInstanceState)
        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")

        setContentView(R.layout.sensor_control)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.ble_playground)
        }


        battery.setOnClickListener {
            checkBattery()
        }
        data.setOnClickListener{
            readData()
        }

        time.setOnClickListener {
            readLoggerTimeReference()
        }

        interval.setOnClickListener {
            readLoggerIntervalTime()
        }

        getLog.setOnClickListener {
            readLoggerData()
        }

        getFlashSize.setOnClickListener {
            readLoggerFlashSize()
        }

        getFlashUsage.setOnClickListener {
            readLoggerFlashUsage()
        }
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }



    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        runOnUiThread {
            val currentLogText = if (log_text_view.text.isEmpty()) {
                "Beginning of log."
            } else {
                log_text_view.text
            }
            log_text_view.text = "$currentLogText\n$formattedMessage"
            log_scroll_view.post { log_scroll_view.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showCharacteristicOptions(characteristic: BluetoothGattCharacteristic) {
        characteristicProperties[characteristic]?.let { properties ->
            selector("Select an action to perform", properties.map { it.action }) { _, i ->
                when (properties[i]) {
                    CharacteristicProperty.Readable -> {
                        log("Reading from ${characteristic.uuid}")
                        ConnectionManager.readCharacteristic(device, characteristic)
                    }
                    CharacteristicProperty.Writable, CharacteristicProperty.WritableWithoutResponse -> {
                        showWritePayloadDialog(characteristic)
                    }
                    CharacteristicProperty.Notifiable, CharacteristicProperty.Indicatable -> {
                        if (notifyingCharacteristics.contains(characteristic.uuid)) {
                            log("Disabling notifications on ${characteristic.uuid}")
                            ConnectionManager.disableNotifications(device, characteristic)
                        } else {
                            log("Enabling notifications on ${characteristic.uuid}")
                            ConnectionManager.enableNotifications(device, characteristic)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showWritePayloadDialog(characteristic: BluetoothGattCharacteristic) {
        val hexField = layoutInflater.inflate(R.layout.edittext_hex_payload, null) as EditText
        alert {
            customView = hexField
            isCancelable = false
            yesButton {
                with(hexField.text.toString()) {
                    if (isNotBlank() && isNotEmpty()) {
                        val bytes = hexToBytes()
                        log("Writing to ${characteristic.uuid}: ${bytes.toHexString()}")
                        ConnectionManager.writeCharacteristic(device, characteristic, bytes)
                    } else {
                        log("Please enter a hex payload to write to ${characteristic.uuid}")
                    }
                }
            }
            noButton {}
        }.show()
        hexField.showKeyboard()
    }

    @OptIn(ExperimentalStdlibApi::class)
    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected from device."
                        positiveButton("OK") { onBackPressed() }
                    }.show()
                }
            }

            onCharacteristicRead = { _, characteristic ->
                if (characteristic.uuid == UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")){
                    batteryLevel.text = "${Integer.decode(characteristic.value.toHexString())}%"
                }else if(characteristic.uuid == UUID.fromString("a8a82636-10a4-11e3-ab8c-f23c91aec05e")){
                    val timestamp = toInt32(characteristic.value)
                    val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(Date(timestamp.toLong()*1000))
                    loggerRefTime.text = date.toString()
                    print(timestamp)
                }else if(characteristic.uuid == UUID.fromString("a8a82634-10a4-11e3-ab8c-f23c91aec05e")){
                    val interValSeconds = toInt16(characteristic.value)
                    intervalText.text = interValSeconds.toString()
                }else if(characteristic.uuid == UUID.fromString("a8a82637-10a4-11e3-ab8c-f23c91aec05e")){
                    val test = characteristic.value
                    print(test)
                }else if(characteristic.uuid == UUID.fromString("a8a82950-10a4-11e3-ab8c-f23c91aec05e")) {
                    val flashSizeTemp = toInt32(characteristic.value)
                    flashSize.text = flashSizeTemp.toString()
                }else if(characteristic.uuid == UUID.fromString("a8a82646-10a4-11e3-ab8c-f23c91aec05e")) {
                    val flashUsageTemp = toInt32(characteristic.value)
                    flashUsage.text = flashUsageTemp.toString()
                }
                log("Read from ${characteristic.uuid}: ${characteristic.value.toHexString()}")
            }

            onCharacteristicWrite = { _, characteristic ->
                log("Wrote to ${characteristic.uuid}")
            }

            onMtuChanged = { _, mtu ->
                log("MTU updated to $mtu")
            }

            onCharacteristicChanged = { _, characteristic ->
                if (characteristic.uuid == UUID.fromString("a8a82631-10a4-11e3-ab8c-f23c91aec05e")){
                    val result = characteristic.value
                    val temp =  (result[0].toInt().and(0xff)).or((result.get(1).toInt().rotateLeft(8)).and(0xff00) )
                    val tempC = -46.85f + 175.72f * temp.toFloat() / 65536.toFloat()
                    temperature.text = "${tempC} C"
                    val hum =  (result[2].toInt().and(0xff)).or((result.get(3).toInt().rotateLeft(8)).and(0xff00) )//.and(0xff.toByte())
                    val relHum = -6.0f + 125.0f * hum.toFloat() / 65536.toFloat()
                    humidity.text = "${relHum}%"
                    }else if(characteristic.uuid == UUID.fromString("a8a82637-10a4-11e3-ab8c-f23c91aec05e")){
                        val test = characteristic.value
                        print(test)
                    }

                log("Value changed on ${characteristic.uuid}: ${characteristic.value.toHexString()}")
            }

            onNotificationsEnabled = { _, characteristic ->
                log("Enabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.add(characteristic.uuid)
            }

            onNotificationsDisabled = { _, characteristic ->
                log("Disabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }

    private enum class CharacteristicProperty {
        Readable,
        Writable,
        WritableWithoutResponse,
        Notifiable,
        Indicatable;

        val action
            get() = when (this) {
                Readable -> "Read"
                Writable -> "Write"
                WritableWithoutResponse -> "Write Without Response"
                Notifiable -> "Toggle Notifications"
                Indicatable -> "Toggle Indications"
            }
    }

    private fun Activity.hideKeyboard() {
        hideKeyboard(currentFocus ?: View(this))
    }

    private fun Context.hideKeyboard(view: View) {
        val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun EditText.showKeyboard() {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.toUpperCase(Locale.US).toInt(16).toByte() }.toByteArray()

    fun toInt32(bytes:ByteArray):Int {
        if (bytes.size != 4) {
            throw Exception("wrong len")
        }
        bytes.reverse()
        return ByteBuffer.wrap(bytes).int
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun toInt16(bytes:ByteArray):Int {
        if (bytes.size != 2) {
            throw Exception("wrong len")
        }
        val result =  (bytes[0].toInt().and(0xff)).or((bytes.get(1).toInt().rotateLeft(8)).and(0xff00) )
        return result
    }

    private fun checkBattery(){
        val batteryLevelCharUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        ConnectionManager.readSensorCharacteristic(device, batteryLevelCharUuid)
    }

    private fun readData(){
        val bluSensorData  = UUID.fromString("a8a82631-10a4-11e3-ab8c-f23c91aec05e")
        ConnectionManager.enableSensorNotifications(device, bluSensorData )
    }

    private fun readLoggerTimeReference(){
        val loggerTimeReference = UUID.fromString("a8a82636-10a4-11e3-ab8c-f23c91aec05e")
        ConnectionManager.readSensorCharacteristic(device, loggerTimeReference)
    }

    private  fun readLoggerIntervalTime(){
        val loggerIntervalTime = UUID.fromString("a8a82634-10a4-11e3-ab8c-f23c91aec05e")
        ConnectionManager.readSensorCharacteristic(device, loggerIntervalTime)
    }

    private  fun readLoggerFlashSize(){
        val loggerFlashSize = UUID.fromString("a8a82950-10a4-11e3-ab8c-f23c91aec05e")
        ConnectionManager.readSensorCharacteristic(device, loggerFlashSize)
    }

    private  fun readLoggerFlashUsage(){
        val loggerFlashUsage = UUID.fromString("a8a82646-10a4-11e3-ab8c-f23c91aec05e")
        ConnectionManager.readSensorCharacteristic(device, loggerFlashUsage)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun readLoggerData(){
        val loggerControl  = UUID.fromString("a8a82635-10a4-11e3-ab8c-f23c91aec05e")
        val loggerData  = UUID.fromString("a8a82637-10a4-11e3-ab8c-f23c91aec05e")
        val test = "1".encodeToByteArray(0,1)
        val test2 = "1".toByteArray()
        val test3 = "1"
        val test4 = byteArrayOf(0x1)
        print(test)
        print(test4)
        characteristics.forEachByIndex { t -> if(t.uuid == loggerData){
            ConnectionManager.enableNotifications(device, t )
        } }

        characteristics.forEachByIndex { t -> if(t.uuid == loggerControl){
            ConnectionManager.writeCharacteristic(device, t, test4)
        } }


    }
}
