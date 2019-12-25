
package com.physicaloid.lib.usb.driver.uart;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.UsbVidList;
import com.physicaloid.lib.framework.SerialCommunicator;
import com.physicaloid.lib.usb.UsbCdcConnection;
import com.physicaloid.lib.usb.UsbVidPid;
import com.physicaloid.misc.RingBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class UartPL2303 extends SerialCommunicator {
        private static final int PL2303_REQTYPE_HOST2DEVICE_VENDOR = 0x40;
        private static final int PL2303_REQTYPE_DEVICE2HOST_VENDOR = 0xC0;
        private static final int PL2303_REQTYPE_HOST2DEVICE = 0x21;

        private static final int PL2303_VENDOR_WRITE_REQUEST = 0x01;
        private static final int PL2303_SET_LINE_CODING = 0x20;
        private static final int PL2303_SET_CONTROL_REQUEST = 0x22;

		private static final String TAG = UartPL2303.class.getSimpleName();
		private boolean DEBUG_SHOW = false;
		private static final int DEFAULT_BAUDRATE = 9600;
		private UsbCdcConnection mUsbConnetionManager;
		private UartConfig mUartConfig;
		private static final int RING_BUFFER_SIZE = 1024;
		private static final int USB_READ_BUFFER_SIZE = 256;
		private static final int USB_WRITE_BUFFER_SIZE = 256;
		private RingBuffer mBuffer;
        private boolean mReadThreadStop = true;
        private UsbDeviceConnection mConnection;
        private UsbEndpoint mEndpointIn;
        private UsbEndpoint mEndpointOut;
        private boolean isOpened;

        private final byte[] defaultSetLine = new byte[]{
                (byte) 0x80, // [0:3] Baud rate (reverse hex encoding 9600:00 00 25 80 -> 80 25 00 00)
                (byte) 0x25,
                (byte) 0x00,
                (byte) 0x00,
                (byte) 0x00, // [4] Stop Bits (0=1, 1=1.5, 2=2)
                (byte) 0x00, // [5] Parity (0=NONE 1=ODD 2=EVEN 3=MARK 4=SPACE)
                (byte) 0x08  // [6] Data Bits (5=5, 6=6, 7=7, 8=8)
        };

		public UartPL2303(Context context) {
                super(context);
				mUsbConnetionManager = new UsbCdcConnection(context);
                mReadThreadStop = true;
                mUartConfig = new UartConfig();
                mBuffer = new RingBuffer(RING_BUFFER_SIZE);
                isOpened = false;
        }
		
		public boolean open(UsbVidPid ids) {
                if(mUsbConnetionManager.open(ids)) {
                        mConnection = mUsbConnetionManager.getConnection();
                        mEndpointIn = mUsbConnetionManager.getEndpointIn();
                        mEndpointOut = mUsbConnetionManager.getEndpointOut();
                        
                        if(!init()) {
                                return false;
                        }
                        if(!setBaudrate(DEFAULT_BAUDRATE)) {
                                return false;
                        }
                        mBuffer.clear();
                        startRead();
                        isOpened = true;
                        return true;
                }
                return false;
        }
		
        @Override
        public boolean open() {
                for(UsbVidList id : UsbVidList.values()) {
                        if(id.getVid() == 0x067B) {
                                if(open(new UsbVidPid(id.getVid(), 0))) {
                                        return true;
                                }
                        }
                }
                return false;
        }			
		private boolean init() {
			if(mConnection == null) {
				return false;
			}
			//Default Setup
        byte[] buf = new byte[1];
        //Specific vendor stuff that I barely understand but It is on linux drivers, So I trust :)
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0404, 0, null) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8383, 0, buf) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0404, 1, null) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8383, 0, buf) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0000, 1, null) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0001, 0, null) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0002, 0x0044, null) < 0)
        //if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0002, 0x0024, null) < 0)
            return false;
        // End of specific vendor stuff
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_CONTROL_REQUEST, 0x0003, 0,null) < 0)
            return false;

               /* byte[] baudByte = new byte[4];

                baudByte[0] = (byte) (DEFAULT_BAUDRATE & 0x000000FF);
                baudByte[1] = (byte) ((DEFAULT_BAUDRATE & 0x0000FF00) >> 8);
                baudByte[2] = (byte) ((DEFAULT_BAUDRATE & 0x00FF0000) >> 16);
                baudByte[3] = (byte) ((DEFAULT_BAUDRATE & 0xFF000000) >> 24);

                byte[] defaultSetLine = new byte[] {
                        baudByte[0], baudByte[1], baudByte[2], baudByte[3], 0x00, 0x00,
                        0x08};*/
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine) < 0)
            return false;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0505, 0x1311, null) < 0)
            return false;
			
		return true;
		}
		
		@Override
        public boolean close() {
                if(mUsbConnetionManager != null) {
                        stopRead();
                        isOpened = false;
                        return mUsbConnetionManager.close();
                }
                return true;
        }
		
		@Override
        public int read(byte[] buf, int size) {
                return mBuffer.get(buf, size);
        }
		
		@Override
        public int write(byte[] buf, int size) {
                if(buf == null) {
                        return 0;
                }
                int offset = 0;
                int write_size;
                int written_size;
                byte[] wbuf = new byte[USB_WRITE_BUFFER_SIZE];

                while(offset < size) {
                        write_size = USB_WRITE_BUFFER_SIZE;

                        if(offset + write_size > size) {
                                write_size = size - offset;
                        }
                        System.arraycopy(buf, offset, wbuf, 0, write_size);

                        written_size = mConnection.bulkTransfer(mEndpointOut, wbuf, write_size, 100);

                        if(written_size < 0) {
                                return -1;
                        }
                        offset += written_size;
                }

                return offset;
        }
        private void stopRead() {
                mReadThreadStop = true;
        }

        private void startRead() {
                if(mReadThreadStop) {
                        mReadThreadStop = false;
                        new Thread(mLoop).start();
                }
        }
		private Runnable mLoop = new Runnable() {

                @Override
                @SuppressWarnings("CallToThreadYield")
                public void run() {
                        try {
                                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                        } catch(Exception e) {
                        }
                        int len;
                        byte[] rbuf = new byte[mEndpointIn.getMaxPacketSize()];
                        UsbRequest response;
                        UsbRequest request = new UsbRequest();
                        request.initialize(mConnection, mEndpointIn);
                        ByteBuffer buf = ByteBuffer.wrap(rbuf);
                        for(;;) {// this is the main loop for transferring
                                len = 0;
                                if(request.queue(buf, rbuf.length)) {
                                        Log.e(TAG,"before requestWait: ");
                                        response = mConnection.requestWait();
                                        Log.e(TAG,"after requestWait: ");
                                        if(response != null) {
                                                len = buf.position();
                                        }
                                        if(len > 0) {
                                                if(DEBUG_SHOW) {
                                                        //Log.e(TAG, "read(" + len + "): " + toHexStr(rbuf, len));
                                                }
                                                Log.e(TAG,"read: " + rbuf);
                                                mBuffer.add(rbuf, len);
                                                onRead(len);
                                        } else if(mBuffer.getBufferdLength() > 0) {
                                                onRead(mBuffer.getBufferdLength());
                                        }

                                } else if(mBuffer.getBufferdLength() > 0) {
                                        onRead(mBuffer.getBufferdLength());
                                }

                                if(mReadThreadStop) {
                                        return;
                                }
                        }
                } // end of run()
        }; // end of runnable
		
		 /**
         * Sets Uart configurations
         *
         * @param config configurations
         *
         * @return true : successful, false : fail
         */
        public boolean setUartConfig(UartConfig config) {
                boolean res;
                boolean ret = true;
                res = setBaudrate(config.baudrate);
                ret = ret && res;

                res = setDataBits(config.dataBits);
                ret = ret && res;

                res = setParity(config.parity);
                ret = ret && res;

                res = setStopBits(config.stopBits);
                ret = ret && res;

                res = setDtrRts(config.dtrOn, config.rtsOn);
                ret = ret && res;

                return ret;
        }
		
		@Override
        public boolean isOpened() {
                return isOpened;
        }
		
		/**
         * Sets baudrate
         *
         * @param baudrate baudrate e.g. 9600
         *
         * @return true : successful, false : fail
         */
        public boolean setBaudrate(int baudrate) {
                //byte[] baudByte = new byte[4];

                defaultSetLine[0] = (byte) (baudrate & 0x000000FF);
                defaultSetLine[1] = (byte) ((baudrate & 0x0000FF00) >> 8);
                defaultSetLine[2] = (byte) ((baudrate & 0x00FF0000) >> 16);
                defaultSetLine[3] = (byte) ((baudrate & 0xFF000000) >> 24);
				/*byte[] defaultSetLine = new byte[] {
                                baudByte[0], baudByte[1], baudByte[2], baudByte[3], 0x00, 0x00,
                                0x08};*/
				int ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
            if(ret < 0) {
                return false;
            }
				mUartConfig.baudrate = baudrate;
                return true;
		}
        @Override
        public boolean setDataBits(int dataBits) {
                int ret = -1;
                switch(dataBits)
                {
                        case 5: //DATA BIT 5
                                if(defaultSetLine[6] != 0x05)
                                {
                                        defaultSetLine[6] = 0x05;
                                        ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case 6: //DATA BIT 6
                                if(defaultSetLine[6] != 0x06)
                                {
                                        defaultSetLine[6] = 0x06;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.DATA_BITS7: //DATA BIT 7
                                if(defaultSetLine[6] != 0x07)
                                {
                                        defaultSetLine[6] = 0x07;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.DATA_BITS8: //DATA BIT 8
                                if(defaultSetLine[6] != 0x08)
                                {
                                        defaultSetLine[6] = 0x08;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        default:
                                defaultSetLine[6] = 0x08;
                                ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                break;
                        //return false;
                }
                if(ret < 0) {
                        if(DEBUG_SHOW) {
                                Log.e(TAG, "Fail to setDataBits");
                        }
                        return false;
                }
                mUartConfig.dataBits = dataBits;
                return true;
        }

        @Override
        public boolean setParity(int parity) {

                int ret = -1;
                switch(parity)
                {
                        case UartConfig.PARITY_NONE: //.PARITY_NONE:
                                if(defaultSetLine[5] != 0x00)
                                {
                                        defaultSetLine[5] = 0x00;
                                        ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.PARITY_ODD: //PARITY_ODD:
                                if(defaultSetLine[5] != 0x01)
                                {
                                        defaultSetLine[5] = 0x01;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.PARITY_EVEN: //PARITY_EVEN:
                                if(defaultSetLine[5] != 0x02)
                                {
                                        defaultSetLine[5] = 0x02;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.PARITY_MARK: //PARITY_MARK:
                                if(defaultSetLine[5] != 0x03)
                                {
                                        defaultSetLine[5] = 0x03;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.PARITY_SPACE: //PARITY_SPACE:
                                if(defaultSetLine[5] != 0x04)
                                {
                                        defaultSetLine[5] = 0x04;
                                        ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        default:
                                defaultSetLine[5] = 0x00;
                                ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);

                                //ret = -1;
                                //return false;
                                break ;
                }
                if(ret < 0) {
                        if(DEBUG_SHOW) {
                                Log.d(TAG, "Fail to setParity");
                        }
                        return false;
                }

                mUartConfig.parity = parity;
                return true;
        }

        @Override
        public boolean setStopBits(int stopBits) {
                int ret = -1;

                switch(stopBits)
                {
                        case UartConfig.STOP_BITS1: //STOP_BITS_1:
                                if(defaultSetLine[4] != 0x00)
                                {
                                        defaultSetLine[4] = 0x00;
                                        ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.STOP_BITS1_5: //STOP_BITS_15
                                if(defaultSetLine[4] != 0x01)
                                {
                                        defaultSetLine[4] = 0x01;
                                        ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        case UartConfig.STOP_BITS2: //STOP_BITS_2:
                                if(defaultSetLine[4] != 0x02)
                                {
                                        defaultSetLine[4] = 0x02;
                                        ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                }
                                break;
                        default:
                                defaultSetLine[4] = 0x00;
                                ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                                break;

                }

                if(ret < 0) {
                        if(DEBUG_SHOW) {
                                Log.d(TAG, "Fail to setStopBits");
                        }
                        return false;
                }
                mUartConfig.stopBits = stopBits;
                return true;
        }

        @Override
        public boolean setDtrRts(boolean dtrOn, boolean rtsOn) {
                int ctrlValue = 0x0000;
                if(dtrOn) {
                        ctrlValue |= 0x0001;
                }
                if(rtsOn) {
                        ctrlValue |= 0x0002;
                }
                /*int ret = mConnection.controlTransfer(0x21, 0x22, ctrlValue, mInterfaceNum, null, 0, 100);
                if(ret < 0) {
                        if(DEBUG_SHOW) {
                                Log.d(TAG, "Fail to setDtrRts");
                        }
                        return false;
                }*/
                mUartConfig.dtrOn = dtrOn;
                mUartConfig.rtsOn = rtsOn;
                return true;
        }

		@Override
        public UartConfig getUartConfig() {
                return mUartConfig;
        }

        @Override
        public int getBaudrate() {
                return mUartConfig.baudrate;
        }

        @Override
        public int getDataBits() {
                return mUartConfig.dataBits;
        }

        @Override
        public int getParity() {
                return mUartConfig.parity;
        }

        @Override
        public int getStopBits() {
                return mUartConfig.stopBits;
        }

        @Override
        public boolean getDtr() {
                return mUartConfig.dtrOn;
        }

        @Override
        public boolean getRts() {
                return mUartConfig.rtsOn;
        }

        @Override
        public void clearBuffer() {
                mBuffer.clear();
        }
		//////////////////////////////////////////////////////////
        // Listener for reading uart
        //////////////////////////////////////////////////////////
        private List<ReadListener> uartReadListenerList = new ArrayList<ReadListener>();
        private boolean mStopReadListener = false;

        @Override
        public void addReadListener(ReadListener listener) {
                uartReadListenerList.add(listener);
        }

        @Override
        @Deprecated
        public void addReadListener(ReadLisener listener) {
                addReadListener((ReadListener)listener);
        }

        @Override
        public void clearReadListener() {
                uartReadListenerList.clear();
        }

        @Override
        public void startReadListener() {
                mStopReadListener = false;
        }

        @Override
        public void stopReadListener() {
                mStopReadListener = true;
        }

        private void onRead(int size) {
                if(mStopReadListener) {
                        return;
                }
                for(ReadListener listener : uartReadListenerList) {
                        listener.onRead(size);
                }
        }
        //////////////////////////////////////////////////////////

        @Override
        public String getPhysicalConnectionName() {
                return Physicaloid.USB_STRING;
        }

        @Override
        public int getPhysicalConnectionType() {
                return Physicaloid.USB;
        }

        @Override
        public void setDebug(boolean flag) {
                DEBUG_SHOW = flag;
        }

        private int setControlCommand(int reqType , int request, int value, int index, byte[] data)
        {
                int dataLength = 0;
                if(data != null)
                        dataLength = data.length;
                int response = mConnection.controlTransfer(reqType, request, value, index, data, dataLength, 0);
                //Log.d(TAG,"setControlCommand:"+ data);
                return response;
        }
}