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

    private final byte[] defaultSetLine = new byte[]{
            (byte) 0x80, // [0:3] Baud rate (reverse hex encoding 9600:00 00 25 80 -> 80 25 00 00)
            (byte) 0x25,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00, // [4] Stop Bits (0=1, 1=1.5, 2=2)
            (byte) 0x00, // [5] Parity (0=NONE 1=ODD 2=EVEN 3=MARK 4=SPACE)
            (byte) 0x08  // [6] Data Bits (5=5, 6=6, 7=7, 8=8)
    };

    private static final String TAG = UartCp210x.class.getSimpleName();
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

    public UartPL2303(Context context) {
        super(context);
        mUsbConnetionManager = new UsbCdcConnection(context);
        mUartConfig = new UartConfig();
        mBuffer = new RingBuffer(RING_BUFFER_SIZE);
        isOpened = false;
    }
    @Override
    public boolean open() {
        for(UsbVidList id : UsbVidList.values()) {
            //if(id.getVid() == 0x067B) {
                if(open(new UsbVidPid(id.getVid(), 0))) {
                    return true;
                }
           // }
        }
        return false;
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
    public boolean close() {
        stopRead();
        isOpened = false;
        PL2303UsbDisable();
        return mUsbConnetionManager.close();
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
        public void run() {
            int len;

            byte[] rbuf = new byte[mEndpointIn.getMaxPacketSize()];
            android.os.Process.setThreadPriority(-20);
            UsbRequest response;
            UsbRequest request = new UsbRequest();
            request.initialize(mConnection, mEndpointIn);
            ByteBuffer buf = ByteBuffer.wrap(rbuf);
            for(;;) {// this is the main loop for transferring
                len = 0;
                if(request.queue(buf, rbuf.length)) {
                    response = mConnection.requestWait();
                    if(response != null) {
                        len = buf.position();
                    }
                    if(len > 0) {
                        mBuffer.add(rbuf, len);
                        onRead(len);
                    } else if(mBuffer.getBufferdLength() > 0) {
                        onRead(mBuffer.getBufferdLength());
                    } else if(mBuffer.getBufferdLength() > 0) {
                        onRead(mBuffer.getBufferdLength());
                    }


                }

                if(mReadThreadStop) {
                    return;
                }

                try {
                    Thread.sleep(1);
                } catch(InterruptedException e) {
                }

            }
        } // end of run()
    }; // end of runnable

    @Override
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

    /**
     * Initializes PL2303 communication
     *
     * @return true : successful, false : fail
     */
    private boolean init() {
        int ret = PL2303UsbEnable();
        if(ret < 0) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isOpened() {
        return isOpened;
    }

    /**
     * Enables PL2303
     *
     * @return positive value : successful, negative value : fail
     */
    private int PL2303UsbEnable() {
        if(mConnection == null) {
            return -1;
        }
        //Default Setup
        byte[] buf = new byte[1];
        //Specific vendor stuff that I barely understand but It is on linux drivers, So I trust :)
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0404, 0, null) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8383, 0, buf) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0404, 1, null) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8484, 0, buf) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_DEVICE2HOST_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x8383, 0, buf) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0000, 1, null) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0001, 0, null) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0002, 0x0044, null) < 0)
            return -1;
        // End of specific vendor stuff
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_CONTROL_REQUEST, 0x0003, 0,null) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine) < 0)
            return -1;
        if(setControlCommand(PL2303_REQTYPE_HOST2DEVICE_VENDOR, PL2303_VENDOR_WRITE_REQUEST, 0x0505, 0x1311, null) < 0)
            return -1;
        return 0;
    }

    /**
     * Disables PL2303
     *
     * @return positive value : successful, negative value : fail
     */
    private int PL2303UsbDisable() {
        if(mConnection == null) {
            return -1;
        }

        return 0;
    }

    /**
     * Gets configurations from PL2303
     *
     * @param request request id
     * @param buf gotten buffer
     * @param size size of getting buffer
     * @return positive value : successful, negative value : fail
     */
    private int PL2303GetConfig(int request, byte[] buf, int size) {
        if(mConnection == null) {
            return -1;
        }

        return 0;
    }



    private int setControlCommand(int reqType , int request, int value, int index, byte[] data)
    {
        int dataLength = 0;
        if(data != null)
            dataLength = data.length;
        int response = mConnection.controlTransfer(reqType, request, value, index, data, dataLength, 100);

        return response;
    }
    @Override
    public boolean setBaudrate(int baudrate) {
        // TO-DO: Allow any baud rate. Why isn't this possible here?
        if(baudrate <= 300) {
            baudrate = 300;
        } else if(baudrate <= 600) {
            baudrate = 600;
        } else if(baudrate <= 1200) {
            baudrate = 1200;
        } else if(baudrate <= 1800) {
            baudrate = 1800;
        } else if(baudrate <= 2400) {
            baudrate = 2400;
        } else if(baudrate <= 4000) {
            baudrate = 4000;
        } else if(baudrate <= 4803) {
            baudrate = 4800;
        } else if(baudrate <= 7207) {
            baudrate = 7200;
        } else if(baudrate <= 9612) {
            baudrate = 9600;
        } else if(baudrate <= 14428) {
            baudrate = 14400;
        } else if(baudrate <= 16062) {
            baudrate = 16000;
        } else if(baudrate <= 19250) {
            baudrate = 19200;
        } else if(baudrate <= 28912) {
            baudrate = 28800;
        } else if(baudrate <= 38601) {
            baudrate = 38400;
        } else if(baudrate <= 51558) {
            baudrate = 51200;
        } else if(baudrate <= 56280) {
            baudrate = 56000;
        } else if(baudrate <= 58053) {
            baudrate = 57600;
        } else if(baudrate <= 64111) {
            baudrate = 64000;
        } else if(baudrate <= 77608) {
            baudrate = 76800;
        } else if(baudrate <= 117028) {
            baudrate = 115200;
        } else if(baudrate <= 129347) {
            baudrate = 128000;
        } else if(baudrate <= 156868) {
            baudrate = 153600;
        } else if(baudrate <= 237832) {
            baudrate = 230400;
        } else if(baudrate <= 254234) {
            baudrate = 250000;
        } else if(baudrate <= 273066) {
            baudrate = 256000;
        } else if(baudrate <= 491520) {
            baudrate = 460800;
        } else if(baudrate <= 567138) {
            baudrate = 500000;
        } else if(baudrate <= 670254) {
            baudrate = 576000;
        } else if(baudrate < 1000000) {
            baudrate = 921600;
        } else if(baudrate > 2000000) {
            baudrate = 2000000;
        }

        byte[] tempBuffer = new byte[4];
        tempBuffer[0] = (byte) (baudrate & 0xff);
        tempBuffer[1] = (byte) (baudrate >> 8 & 0xff);
        tempBuffer[2] = (byte) (baudrate >> 16 & 0xff);
        tempBuffer[3] = (byte) (baudrate >> 24 & 0xff);
        if(tempBuffer[0] != defaultSetLine[0] || tempBuffer[1] != defaultSetLine[1] || tempBuffer[2] != defaultSetLine[2]
                || tempBuffer[3] != defaultSetLine[3])
        {
            defaultSetLine[0] = tempBuffer[0];
            defaultSetLine[1] = tempBuffer[1];
            defaultSetLine[2] = tempBuffer[2];
            defaultSetLine[3] = tempBuffer[3];
            int ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
            if(ret < 0) {
                return false;
            }
        }
        mUartConfig.baudrate = baudrate;
        return true;
    }

    @Override
    public boolean setDataBits(int dataBits) {

        switch(dataBits)
        {
            case 5: //DATA BIT 5
                if(defaultSetLine[6] != 0x05)
                {
                defaultSetLine[6] = 0x05;
                setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
        }
                break;
            case 6: //DATA BIT 6
                if(defaultSetLine[6] != 0x06)
                {
                    defaultSetLine[6] = 0x06;
                    setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 7: //DATA BIT 7
                if(defaultSetLine[6] != 0x07)
                {
                    defaultSetLine[6] = 0x07;
                    setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 8: //DATA BIT 8
                if(defaultSetLine[6] != 0x08)
                {
                    defaultSetLine[6] = 0x08;
                    setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            default:
                return false;
        }
        mUartConfig.dataBits = dataBits;
        return true;
    }

    @Override
    public boolean setParity(int parity) {

        int ret = 0;
        switch(parity)
        {
            case 0: //.PARITY_NONE:
                if(defaultSetLine[5] != 0x00)
                {
                    defaultSetLine[5] = 0x00;
                    ret = setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 1: //PARITY_ODD:
                if(defaultSetLine[5] != 0x01)
                {
                    defaultSetLine[5] = 0x01;
                    ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 2: //PARITY_EVEN:
                if(defaultSetLine[5] != 0x02)
                {
                    defaultSetLine[5] = 0x02;
                    ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 3: //PARITY_MARK:
                if(defaultSetLine[5] != 0x03)
                {
                    defaultSetLine[5] = 0x03;
                    ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 4: //PARITY_SPACE:
                if(defaultSetLine[5] != 0x04)
                {
                    defaultSetLine[5] = 0x04;
                    ret =setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            default:
                ret = -1;
                return false;
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

        switch(stopBits)
        {
            case 1: //STOP_BITS_1:
                if(defaultSetLine[4] != 0x00)
                {
                    defaultSetLine[4] = 0x00;
                    setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 3: //STOP_BITS_15
                if(defaultSetLine[4] != 0x01)
                {
                    defaultSetLine[4] = 0x01;
                    setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            case 2: //STOP_BITS_2:
                if(defaultSetLine[4] != 0x02)
                {
                    defaultSetLine[4] = 0x02;
                    setControlCommand(PL2303_REQTYPE_HOST2DEVICE, PL2303_SET_LINE_CODING, 0x0000, 0, defaultSetLine);
                }
                break;
            default:
                return false;
        }
        mUartConfig.stopBits = stopBits;
        return true;
    }

    @Override
    public boolean setDtrRts(boolean dtrOn, boolean rtsOn) {
        //TODO
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

    /**
     * Transfers int to little endian byte array
     *
     * @param in integer value
     * @param out 4 or less length byte array
     */
    private void intToLittleEndianBytes(int in, byte[] out) {
        if(out == null) {
            return;
        }
        if(out.length > 4) {
            return;
        }
        for(int i = 0; i < out.length; i++) {
            out[i] = (byte) ((in >> (i * 8)) & 0x000000FF);
        }
    }

    /**
     * Transfers little endian byte array to int
     *
     * @param in 4 or less length byte array
     * @return integer value
     */
    private int littleEndianBytesToInt(byte[] in) {
        if(in == null) {
            return 0;
        }
        if(in.length > 4) {
            return 0;
        }
        int ret = 0;
        for(int i = 0; i < in.length; i++) {
            ret |= (((int) in[i]) & 0x000000FF) << (i * 8);
        }
        return ret;
    }

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
}
