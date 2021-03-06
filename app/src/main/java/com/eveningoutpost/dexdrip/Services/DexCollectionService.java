package com.eveningoutpost.dexdrip.Services;
/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.Models.ActiveBluetoothDevice;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Sensor;
import com.eveningoutpost.dexdrip.UtilityModels.CollectionServiceStarter;
import com.eveningoutpost.dexdrip.UtilityModels.ForegroundServiceStarter;
import com.eveningoutpost.dexdrip.UtilityModels.HM10Attributes;
import com.eveningoutpost.dexdrip.Models.TransmitterData;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;


public class DexCollectionService extends Service {
    private final static String TAG = DexCollectionService.class.getSimpleName();
    private String mDeviceName;
    private String mDeviceAddress;
    private boolean is_connected = false;
    SharedPreferences prefs;
    private static byte bridgeBattery = 0;

    public DexCollectionService dexCollectionService;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private ForegroundServiceStarter foregroundServiceStarter;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic mCharacteristic;
    private BluetoothGattService mService;
    //queues for GattDescriptor and GattCharacteristic to ensure we get all messages and can clear the the message once it is processed.
    private Queue<BluetoothGattDescriptor> descriptorWriteQueue = new LinkedList<BluetoothGattDescriptor>();
    private Queue<BluetoothGattCharacteristic> characteristicReadQueue = new LinkedList<BluetoothGattCharacteristic>();
    //int mStartMode;
    long lastPacketTime;
    private static byte[] lastdata=null;

    private Context mContext = null;

    private static final int STATE_DISCONNECTED = BluetoothProfile.STATE_DISCONNECTED;
    private static final int STATE_DISCONNECTING = BluetoothProfile.STATE_DISCONNECTING;
    private static final int STATE_CONNECTING = BluetoothProfile.STATE_CONNECTING;
    private static final int STATE_CONNECTED = BluetoothProfile.STATE_CONNECTED;

    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static UUID DexDripDataService =
            UUID.fromString(HM10Attributes.HM_10_SERVICE);
    public final static UUID DexDripDataCharacteristic =
            UUID.fromString(HM10Attributes.HM_RX_TX);

    @Override
    public void onCreate() {

        foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), this);
        foregroundServiceStarter.start();
        mContext = getApplicationContext();
        dexCollectionService = this;
        listenForChangeInSettings();
        this.startService(new Intent(this, SyncService.class));
        Log.w(TAG, "STARTING SERVICE");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        attemptConnection();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "onDestroy entered");
        setRetryTimer();
        close();
        foregroundServiceStarter.stop();
        Log.w(TAG, "SERVICE STOPPED");
    }

    public void writeGattDescriptor(BluetoothGattDescriptor d) {
        //put the descriptor into the write queue
        descriptorWriteQueue.add(d);
        //if there is only 1 item in the queue, then write it.  If more than 1, we handle asynchronously in the callback above
        if (descriptorWriteQueue.size() == 1) {
            mBluetoothGatt.writeDescriptor(d);
        }
    }


    public static byte getBridgeBattery(){
        return bridgeBattery;
    }

    public boolean isUnitsMmol(){
        return (PreferenceManager.getDefaultSharedPreferences(this).getString("units", "mmol").compareTo("mmol") !=0 );
    }

    public static String getBridgeBatteryAsString() {
        return String.format("%d",bridgeBattery);
    }
    //TODO: Move this somewhere more reusable
    public void listenForChangeInSettings() {
        SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                if (key.compareTo("run_service_in_foreground") == 0) {
                    if (prefs.getBoolean("run_service_in_foreground", false)) {
                        foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), dexCollectionService);
                        foregroundServiceStarter.start();
                        Log.w(TAG, "Moving to foreground");
                        setRetryTimer();
                    } else {
                        dexCollectionService.stopForeground(true);
                        Log.w(TAG, "Removing from foreground");
                        setRetryTimer();
                    }
                }
                if (key.compareTo("dex_collection_method") == 0) {
                    CollectionServiceStarter collectionServiceStarter = new CollectionServiceStarter();
                    collectionServiceStarter.start(getApplicationContext());
                }
            }
        };
        prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void attemptConnection() {
        if (device != null) {
            mConnectionState = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
        }
        Log.w(TAG, "Connection state: " + mConnectionState);
        if (mConnectionState == STATE_DISCONNECTED || mConnectionState == STATE_DISCONNECTING) {
            ActiveBluetoothDevice btDevice = new Select().from(ActiveBluetoothDevice.class)
                    .orderBy("_ID desc")
                    .executeSingle();
            if (btDevice != null) {
                mDeviceName = btDevice.name;
                mDeviceAddress = btDevice.address;

                if (mBluetoothManager == null) {
                    mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                    if (mBluetoothManager == null) {
                        Log.w(TAG, "Unable to initialize BluetoothManager.");
                    }
                }
                if (mBluetoothManager != null) {
                    mBluetoothAdapter = mBluetoothManager.getAdapter();
                    if (mBluetoothAdapter == null) {
                        Log.w(TAG, "Unable to obtain a BluetoothAdapter.");
                        setRetryTimer();
                    }
                    is_connected = connect(mDeviceAddress);
                    if (is_connected) {
                        Log.w(TAG, "attemptConnection: connected to device");
                    } else {
                        Log.w(TAG, "attemptConnection: Unable to connect to device");
                        setRetryTimer();
                    }

                } else {
                    Log.w(TAG, "attemptConnection: Still no bluetooth Manager");
                    setRetryTimer();
                }
            } else {
                Log.w(TAG, "attemptConnection: No bluetooth device to try to connect to");
                setRetryTimer();
            }
        }
    }

    public void setRetryTimer() {
        Calendar calendar = Calendar.getInstance();
        final long time_delay = calendar.getTimeInMillis() + 1000;
        AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarm.setExact(alarm.RTC_WAKEUP, time_delay, PendingIntent.getService(this, 0, new Intent(this, DexCollectionService.class), 0));
        Log.w(TAG, "setRetryTimer Retry set for " + ((time_delay - calendar.getTimeInMillis()) / 1000) + "sec from now!");
    }

    private final BluetoothGattCallback mGattCallback;

    {
        mGattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mConnectionState = STATE_CONNECTED;
                    Log.w(TAG, "onConnectionStateChange: Connected to GATT server.");
                    //refreshDeviceCache(gatt);
                    if(mBluetoothGatt!= null) {
                        Log.w(TAG, "onConnectionStateChange: Attempting to start service discovery: " +
                                mBluetoothGatt.discoverServices());
                        setRetryTimer();
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mConnectionState = STATE_DISCONNECTED;
                    //close();
                    Log.w(TAG, "onConnectionStateChange: Disconnected from GATT server.");
                    //refreshDeviceCache(gatt);
                    setRetryTimer();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    for (BluetoothGattService gattService : mBluetoothGatt.getServices()) {
                        //if(mService == null && (!gattService.equals(mService))) {
                            //mService = gattService;
                            Log.w(TAG, "onServicesDiscovered: Service Found");
                            for (BluetoothGattCharacteristic gattCharacteristic : gattService.getCharacteristics()) {
                                Log.w(TAG, "onServicesDiscovered: Characteristic Found");
                                if(gattCharacteristic.equals(mCharacteristic)) {
                                    Log.v(TAG, "onServicesDiscovered: Already have that characteristic");
                                    return;
                                }
                                //mCharacteristic = gattCharacteristic;
                                setCharacteristicNotification(gattCharacteristic, true);
                            }
                        //} else {
                            //Log.v(TAG, "onServicesDiscovered: Already have that service");
                        }
                        Log.w(TAG, "onServicesDiscovered received success: " + status);
                    //}
                } else {
                    Log.w(TAG, "onServicesDiscovered received: " + status);
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt,
                                             BluetoothGattCharacteristic characteristic,
                                             int status) {
                Log.w(TAG, "onCharacteristicRead entered.");
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
                } else {
                    Log.w(TAG, "onCharacteristicRead error: " + status);
                }
                if (characteristicReadQueue.size() > 0)
                    mBluetoothGatt.readCharacteristic(characteristicReadQueue.element());
                characteristicReadQueue.remove();
                Log.w(TAG, "onCharacteristicRead exited.");
            }

            @Override
            public void onCharacteristicChanged (BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic){
                //final byte[] data;
                Log.w(TAG, "onCharacteristicChanged entered");
                //data = characteristic.getValue();
                //if(lastdata == null) lastdata = characteristic.getValue();
                //if(Arrays.equals(lastdata, data) && lastdata != null) {
                //    Log.w(TAG, "onCharacteristicChanged: duplicate packet.  Ignoring");
                //    return;
                //}
                Log.w(TAG, "onCharacteristicChanged: new packet.");
                //lastdata = data.clone();;
                characteristicReadQueue.add(characteristic);
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristicReadQueue.poll());
                //if(characteristicReadQueue.size()>0) characteristicReadQueue.remove();
            }

/*            @Override
            public void onGetService(BluetoothGatt gatt, BluetoothGattService service){
                if( gatt == null) {
                    Log.v(TAG, "onGetService: BluetoothGatt not initialised");
                    return;
                }
                if(service.equals(mService)){
                    Log.v(TAG, "onGetService: Already have that service");
                    return;
                } else {
                    mService = service;
                    return;
                }
            }
*/
            @Override
            public void onDescriptorWrite (BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status){
               if (status == BluetoothGatt.GATT_SUCCESS) {
                   Log.d(TAG, "onDescriptorWrite: Wrote GATT Descriptor successfully.");
                } else {
                    Log.d(TAG, "onDescriptorWrite: Error writing GATT Descriptor: " + status);
                }
                descriptorWriteQueue.remove();  //pop the item that we just finishing writing

                if (descriptorWriteQueue.size() > 0) // if there is more to write, do it!
                    mBluetoothGatt.writeDescriptor(descriptorWriteQueue.element());
                /* else if(characteristicReadQueue.size() > 0)
                    mBluetoothGatt.readCharacteristic(characteristicReadQueue.element());
                */
            }

        };
    }



    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        Log.w(TAG, "broadcastUpdate got action: "+action+", characteristic: "+characteristic.toString());
        final byte[] data = characteristic.getValue();
        if(lastdata != null){
            if(data !=null && data.length >0 && !Arrays.equals(lastdata, data)){
                Log.v(TAG, "broadcastUpdate: new data.");
                setSerialDataToTransmitterRawData(data, data.length);
                lastdata = data;
            } else if(lastdata.equals(data)){
                Log.v(TAG,"broadcastUpdate: duplicate data, ignoring");
                return;
            }
        } else if (data != null && data.length > 0) {
            setSerialDataToTransmitterRawData(data, data.length);
            lastdata = data;
        }
    }

    private boolean sendBtMessage(final ByteBuffer message){
        //check mBluetoothGatt is available
        Log.w(TAG,"sendBtMessage: entered");
        if (mBluetoothGatt == null) {
            Log.e(TAG, "sendBtMessage: lost connection");
            return false;
        }

        byte[] value = message.array();
        Log.w(TAG, "sendBtMessage: sending message");
        mCharacteristic.setValue(value);
        boolean status = mBluetoothGatt.writeCharacteristic(mCharacteristic);
        return status;
    }

    private Integer convertSrc(final String Src) {
        Integer res = 0;
        res |= getSrcValue(Src.charAt(0)) << 20;
        res |= getSrcValue(Src.charAt(1)) << 15;
        res |= getSrcValue(Src.charAt(2)) << 10;
        res |= getSrcValue(Src.charAt(3)) << 5;
        res |= getSrcValue(Src.charAt(4));
        return res;
    }
    private int getSrcValue(char ch){
        int i;
        char[] cTable = { '0', '1', '2', '3', '4', '5', '6', '7','8', '9', 'A', 'B', 'C', 'D', 'E', 'F','G', 'H', 'J', 'K', 'L', 'M', 'N', 'P','Q', 'R', 'S', 'T', 'U', 'W', 'X', 'Y' };
        for(i=0; i<cTable.length; i++){
            if(cTable[i] == ch)  break;
        }
        return i;
    }

    public class LocalBinder extends Binder {
        DexCollectionService getService() {
            return DexCollectionService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();
    public boolean initialize() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.w(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }
        return true;
    }

    public boolean connect(final String address) {
        Log.w(TAG, "connect: CONNECTING TO DEVICE "+ address);
        //Log.w(TAG, address);
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "connect: BluetoothAdapter not initialized or unspecified address.");
            return false;
        }
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.w(TAG, "connect: Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                Log.w(TAG, "connect: Connected");
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                Log.w(TAG, "connect: Not Connected");
                setRetryTimer();
                return false;
            }
        }
        device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "connect: Device not found.  Unable to connect.");
            return false;
        }
        mBluetoothGatt = device.connectGatt(this, true, mGattCallback);
        refreshDeviceCache(mBluetoothGatt);
        Log.w(TAG, "connect: Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        Log.w(TAG,"disconnect: Disconnecting BT");
        mBluetoothGatt.disconnect();
    }

    public void close() {
        Log.w(TAG,"close: Closing Connection");
        disconnect();
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        mCharacteristic = null;
        mConnectionState = STATE_DISCONNECTED;
    }


    private boolean refreshDeviceCache(BluetoothGatt gatt){
        try {
            BluetoothGatt localBluetoothGatt = gatt;
            Method localMethod = localBluetoothGatt.getClass().getMethod("refresh", new Class[0]);
            if (localMethod != null) {
                boolean bool = ((Boolean) localMethod.invoke(localBluetoothGatt, new Object[0])).booleanValue();
                return bool;
            }
        }
        catch (Exception localException) {
            Log.e(TAG, "refreshDeviceCache: An exception occurred while refreshing device");
        }
        Log.w(TAG, "refreshDeviceCache: Completed.");
        return false;
    }
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "readCharacteristicBluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        if (characteristic.equals(mCharacteristic)) {
            Log.v(TAG,"setCharacteristicNotification: Already have that characteristic");
            return;
        }
        Log.w(TAG, "setCharacteristicNotification - UUID FOUND: " + characteristic.getUuid());
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        Log.w(TAG, "UUID FOUND: " + characteristic.getUuid());
        if (DexDripDataCharacteristic.equals(characteristic.getUuid())) {
            Log.w(TAG, "setCharacteristicNotification - UUID MATCH FOUND!!! " + characteristic.getUuid());
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(HM10Attributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            //mBluetoothGatt.writeDescriptor(descriptor);
            writeGattDescriptor(descriptor);
            Log.w(TAG, "setCharacteristicNotification: Saving characteristic "+characteristic.toString());
            mCharacteristic = characteristic;
        }
    }

    public void setSerialDataToTransmitterRawData(byte[] buffer, int len) {

        Log.w(TAG, "setSerialDataToTransmitterRawData: received some data!");
        if (PreferenceManager.getDefaultSharedPreferences(this).getString("dex_collection_method", "DexbridgeWixel").compareTo("DexbridgeWixel") ==0) {
            Log.w(TAG, "setSerialDataToTransmitterRawData: Dealing with Dexbridge packet!");
            int DexSrc;
            int TransmitterID;
            String TxId;
            Calendar c = Calendar.getInstance();
            long secondsNow = c.getTimeInMillis();
            ByteBuffer tmpBuffer = ByteBuffer.allocate(len);
            tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
            tmpBuffer.put(buffer, 0, len);
            ByteBuffer txidMessage = ByteBuffer.allocate(6);
            txidMessage.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer[0] == 0x07 && buffer[1] == -15) {
                //We have a Beacon packet.  Get the TXID value and compare with dex_txid
                Log.w(TAG, "setSerialDataToTransmitterRawData: Received Beacon packet.");
                //DexSrc starts at Byte 2 of a Beacon packet.
                DexSrc = tmpBuffer.getInt(2);
                TxId = PreferenceManager.getDefaultSharedPreferences(this).getString("dex_txid", "00000");
                TransmitterID = convertSrc(TxId);
                if (TxId.compareTo("0000") != 0 &&Integer.compare(DexSrc, TransmitterID) != 0) {
                    Log.w(TAG, "setSerialDataToTransmitterRawData: TXID wrong.  Expected "+ TransmitterID +" but got " +DexSrc);
                    txidMessage.put(0, (byte) 0x06);
                    txidMessage.put(1, (byte) 0x01);
                    txidMessage.putInt(2, TransmitterID);
                    sendBtMessage(txidMessage);
                }
                return;
            }
            if (buffer[0] == 0x11 && buffer[1] == 0x00) {
                //we have a data packet.  Check to see if the TXID is what we are expecting.
                Log.w(TAG, "setSerialDataToTransmitterRawData: Received Data packet");
                //make sure we are not processing a packet we already have
                if(secondsNow - lastPacketTime < 60000) {
                    Log.v(TAG, "setSerialDataToTransmitterRawData: Received Duplicate Packet.  Exiting.");
                    return;
                } else {
                    lastPacketTime = secondsNow;
                }
                if(len >= 0x11) {
                    //DexSrc starts at Byte 12 of a data packet.
                    DexSrc = tmpBuffer.getInt(12);
                    TxId = PreferenceManager.getDefaultSharedPreferences(this).getString("dex_txid", "00000");
                    TransmitterID = convertSrc(TxId);
                    if (Integer.compare(DexSrc, TransmitterID) != 0) {
                        Log.w(TAG, "TXID wrong.  Expected " + TransmitterID + " but got " + DexSrc);
                        txidMessage.put(0, (byte) 0x06);
                        txidMessage.put(1, (byte) 0x01);
                        txidMessage.putInt(2, TransmitterID);
                        sendBtMessage(txidMessage);
                    }
                    bridgeBattery = ByteBuffer.wrap(buffer).get(11);
                    //All is OK, so process it.
                    //first, tell the wixel it is OK to sleep.
                    Log.d(TAG,"setSerialDataToTransmitterRawData: Sending Data packet Ack, to put wixel to sleep");
                    ByteBuffer ackMessage = ByteBuffer.allocate(2);
                    ackMessage.put(0, (byte)0x02);
                    ackMessage.put(1, (byte)0xF0);
                    sendBtMessage(ackMessage);
                    TransmitterData transmitterData = TransmitterData.create(buffer, len);
                    if (transmitterData != null) {
                        Sensor sensor = Sensor.currentSensor();
                        if (sensor != null) {
                            sensor.latest_battery_level = transmitterData.sensor_battery_level;
                            sensor.save();

                            //BgReading bgReading = BgReading.create(transmitterData.raw_data, this);
                            BgReading bgReading = BgReading.create(transmitterData.raw_data, transmitterData.filtered_data, this);
                            Intent intent = new Intent("com.eveningoutpost.dexdrip.DexCollectionService.SAVED_BG");
                            sendBroadcast(intent);

                        } else {
                            Log.w(TAG, "setSerialDataToTransmitterRawData: No Active Sensor, Data only stored in Transmitter Data");
                        }
                    }
                }
            }
        } else {
            TransmitterData transmitterData = TransmitterData.create(buffer, len);
            if (transmitterData != null) {
                Sensor sensor = Sensor.currentSensor();
                if (sensor != null) {
                    sensor.latest_battery_level = transmitterData.sensor_battery_level;
                    sensor.save();

                    //BgReading bgReading = BgReading.create(transmitterData.raw_data, this);
                    BgReading bgReading = BgReading.create(transmitterData.raw_data, transmitterData.filtered_data, this);
                    Intent intent = new Intent("com.eveningoutpost.dexdrip.DexCollectionService.SAVED_BG");
                    sendBroadcast(intent);

                } else {
                    Log.w(TAG, "setSerialDataToTransmitterRawData: No Active Sensor, Data only stored in Transmitter Data");
                }
            }
        }
    }
}
