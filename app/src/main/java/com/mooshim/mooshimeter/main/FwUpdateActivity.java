/**************************************************************************************************
  Filename:       FwUpdateActivity.java
  Revised:        $Date: 2013-09-05 05:55:20 +0200 (to, 05 sep 2013) $
  Revision:       $Revision: 27614 $

  Copyright (c) 2013 - 2014 Texas Instruments Incorporated

  All rights reserved not granted herein.
  Limited License. 

  Texas Instruments Incorporated grants a world-wide, royalty-free,
  non-exclusive license under copyrights and patents it now or hereafter
  owns or controls to make, have made, use, import, offer to sell and sell ("Utilize")
  this software subject to the terms herein.  With respect to the foregoing patent
  license, such license is granted  solely to the extent that any such patent is necessary
  to Utilize the software alone.  The patent license shall not apply to any combinations which
  include this software, other than combinations with devices manufactured by or for TI (TI Devices).
  No hardware patent is licensed hereunder.

  Redistributions must preserve existing copyright notices and reproduce this license (including the
  above copyright notice and the disclaimer and (if applicable) source code license limitations below)
  in the documentation and/or other materials provided with the distribution

  Redistribution and use in binary form, without modification, are permitted provided that the following
  conditions are met:

    * No reverse engineering, decompilation, or disassembly of this software is permitted with respect to any
      software provided in binary form.
    * any redistribution and use are licensed by TI for use only with TI Devices.
    * Nothing shall obligate TI to provide you with source code for the software licensed and provided to you in object code.

  If software source code is provided to you, modification and redistribution of the source code are permitted
  provided that the following conditions are met:

    * any redistribution and use of the source code, including any resulting derivative works, are licensed by
      TI for use only with TI Devices.
    * any redistribution and use of any object code compiled from the source code and any resulting derivative
      works, are licensed by TI for use only with TI Devices.

  Neither the name of Texas Instruments Incorporated nor the names of its suppliers may be used to endorse or
  promote products derived from this software without specific prior written permission.

  DISCLAIMER.

  THIS SOFTWARE IS PROVIDED BY TI AND TIS LICENSORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
  BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
  IN NO EVENT SHALL TI AND TIS LICENSORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
  OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
  OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
  POSSIBILITY OF SUCH DAMAGE.


 **************************************************************************************************/
package com.mooshim.mooshimeter.main;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

import android.app.Activity;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.text.Html;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.mooshim.mooshimeter.R;
import com.mooshim.mooshimeter.common.BLEUtil;
import com.mooshim.mooshimeter.common.MooshimeterDevice;
import com.mooshim.mooshimeter.util.Conversion;

public class FwUpdateActivity extends Activity {
  public final static String EXTRA_MESSAGE = "com.example.ti.ble.sensortag.MESSAGE";
  // Log
  private static String TAG = "FwUpdateActivity";

  // Activity
  private static final int FILE_ACTIVITY_REQ = 0;

  // Programming parameters

  private static final int FILE_BUFFER_SIZE = 0x40000;
  private static final String FW_CUSTOM_DIRECTORY = Environment.DIRECTORY_DOWNLOADS;
  private static final String FW_FILE_A = "Mooshimeter.bin";

  private static final int OAD_BLOCK_SIZE = 16;
  private static final int HAL_FLASH_WORD_SIZE = 4;
  private static final int OAD_BUFFER_SIZE = 2 + OAD_BLOCK_SIZE;
  private static final int OAD_IMG_HDR_SIZE = 8;
	private static final long TIMER_INTERVAL = 1000;
	
	private static final int SEND_INTERVAL = 20; // Milliseconds (make sure this is longer than the connection interval)
	private static final int BLOCKS_PER_CONNECTION = 1; // May sent up to four blocks per connection

  // GUI
  private TextView mTargImage;
  private TextView mFileImage;
  private TextView mProgressInfo;
  private TextView mLog;
  private ProgressBar mProgressBar;
  private Button mBtnStart;

  // BLE
  private BluetoothGattService mConnControlService;
  private DeviceActivity mDeviceActivity = null;
  private BLEUtil mBLEUtil = null;

  // Programming
  private final byte[] mFileBuffer = new byte[FILE_BUFFER_SIZE];
  private final byte[] mOadBuffer = new byte[OAD_BUFFER_SIZE];
  private ImgHdr mFileImgHdr = new ImgHdr();
  private ImgHdr mTargImgHdr = new ImgHdr();
  private Timer mTimer = null;
  private ProgInfo mProgInfo = new ProgInfo();
  private TimerTask mTimerTask = null;

  // Housekeeping
  private boolean mProgramming = false;
  private final Semaphore pacer = new Semaphore(1);


  public FwUpdateActivity() {
      mBLEUtil = BLEUtil.getInstance(this);
      if( !mBLEUtil.setPrimaryService(MooshimeterDevice.mUUID.OAD_SERVICE_UUID) ) {
          Log.e(TAG, "Failed to find OAD service");
          finish();
      }

    // Service information
    //mConnControlService = mDeviceActivity.getConnControlService();
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.d(TAG, "onCreate");

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_fwupdate);

    // Icon padding
    ImageView view = (ImageView) findViewById(android.R.id.home);
    view.setPadding(10, 0, 20, 10);

    // Context title
    setTitle(R.string.title_oad);

    // Initialize widgets
    mProgressInfo = (TextView) findViewById(R.id.tw_info);
    mTargImage = (TextView) findViewById(R.id.tw_target);
    mFileImage = (TextView) findViewById(R.id.tw_file);
    mLog = (TextView) findViewById(R.id.tw_log);
    mProgressBar = (ProgressBar) findViewById(R.id.pb_progress);
    mBtnStart = (Button) findViewById(R.id.btn_start);
    mBtnStart.setEnabled(false);

    loadFile(FW_FILE_A, true);
    updateGui();
  }

  @Override
  public void onDestroy() {
    Log.d(TAG, "onDestroy");
    super.onDestroy();
    if (mTimerTask != null)
      mTimerTask.cancel();
    mTimer = null;
  }

  @Override
  public void onBackPressed() {
    Log.d(TAG, "onBackPressed");
    if (mProgramming) {
      Toast.makeText(this, R.string.prog_ogoing, Toast.LENGTH_LONG).show();
    } else
      super.onBackPressed();
  }
  
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    Log.d(TAG, "onOptionsItemSelected");
    // Handle presses on the action bar items
    switch (item.getItemId()) {
    // Respond to the action bar's Up/Home button
    case android.R.id.home:
      onBackPressed();
      return true;
    default:
      return super.onOptionsItemSelected(item);
    }
  }
  
  @Override 
  protected void onResume()
  {
    super.onResume();
  }

  @Override
  protected void onPause() {
  	super.onPause();
  }

  public void onStart(View v) {
    if (mProgramming) {
      stopProgramming();
    } else {
      startProgramming();
    }
  }

  private void startProgramming() {
    mLog.append("Programming started\n");
    mProgramming = true;
    updateGui();

    // Update connection parameters
    mBLEUtil.setConnectionInterval((short)20, (short)1000, new BLEUtil.BLEUtilCB() {
        @Override
        public void run() {
            // Send image notification
            final byte[] buf = mFileImgHdr.pack();
            mBLEUtil.enableNotify(MooshimeterDevice.mUUID.OAD_IMAGE_BLOCK, true, new BLEUtil.BLEUtilCB() {
                @Override
                public void run() {
                    mBLEUtil.enableNotify(MooshimeterDevice.mUUID.OAD_IMAGE_IDENTIFY, true, new BLEUtil.BLEUtilCB() {
                        @Override
                        public void run() {
                            mBLEUtil.send(MooshimeterDevice.mUUID.OAD_IMAGE_IDENTIFY, buf, new BLEUtil.BLEUtilCB() {
                                @Override
                                public void run() {
                                    if (error != BluetoothGatt.GATT_SUCCESS) {
                                        Log.e(TAG, "Error sending identify");
                                    } else {
                                        // Initialize stats
                                        mProgInfo.reset();
                                        mTimer = new Timer();
                                        mTimerTask = new ProgTimerTask();
                                        mTimer.scheduleAtFixedRate(mTimerTask, 0, TIMER_INTERVAL);
                                        programBlock();
                                    }
                                }
                            });
                        }
                    }, new BLEUtil.BLEUtilCB() {
                        @Override
                        public void run() {
                            Log.d(TAG,"OAD Image identify notification!");
                        }
                    });
                }
            }, new BLEUtil.BLEUtilCB() {
                @Override
                public void run() {
                    // After each block notify, release the pacer and allow the next block to fly
                    pacer.release();
                }
            });
        }
    });

  }

  private void stopProgramming() {
    mTimer.cancel();
    mTimer.purge();
    mTimerTask.cancel();
    mTimerTask = null;

    mProgramming = false;
    mProgressInfo.setText("");
    mProgressBar.setProgress(0);
    updateGui();

    if (mProgInfo.iBlocks == mProgInfo.nBlocks) {
      mLog.setText("Programming complete!\n");
    } else {
      mLog.append("Programming cancelled\n");
    }
  }

  private void updateGui() {
  	if (mProgramming) {
  		// Busy: stop label, progress bar, disabled file selector
  		mBtnStart.setText(R.string.cancel);
  	} else {
  		// Idle: program label, enable file selector
  		mProgressBar.setProgress(0);
  		mBtnStart.setText(R.string.start_prog);
  	}
  }

  private boolean loadFile(String filepath, boolean isAsset) {
    boolean fSuccess = false;

    // Load binary file
    try {
      // Read the file raw into a buffer
      InputStream stream;
      if (isAsset) {
        stream = getAssets().open(filepath);
      } else {
        File f = new File(filepath);
        stream = new FileInputStream(f);
      }
      stream.read(mFileBuffer, 0, mFileBuffer.length);
      stream.close();
    } catch (IOException e) {
      // Handle exceptions here
      mLog.setText("File open failed: " + filepath + "\n");
      return false;
    }

    // Show image info
    mFileImgHdr.unpack(mFileBuffer);
    displayImageInfo(mFileImage, mFileImgHdr);

    // Verify image types
    int resid = R.style.dataStyle1;
    mFileImage.setTextAppearance(this, resid);

    // Enable programming button only if image types differ
    mBtnStart.setEnabled(true);

    // Expected duration
    displayStats();

    // Log
    mLog.setText("Image Loaded.\n");
    mLog.append("Ready to program device!\n");

    updateGui();

    return fSuccess;
  }

  private void displayImageInfo(TextView v, ImgHdr h) {
    int imgSize = h.len * 4;
    String s = String.format("Ver.: %d Build: %d Size: %d", h.ver, h.build_time, imgSize);
    v.setText(Html.fromHtml(s));
  }

  private void displayStats() {
    String txt;
    int byteRate;
    int sec = mProgInfo.iTimeElapsed / 1000;
    if (sec > 0) {
      byteRate = mProgInfo.iBytes / sec;
    } else {
      byteRate = 0;
      return;
    }
    float timeEstimate;

    timeEstimate = ((float)(mFileImgHdr.len *4) / (float)mProgInfo.iBytes) * sec;

    txt = String.format("Time: %d / %d sec", sec, (int)timeEstimate);
    txt += String.format("    Bytes: %d (%d/sec)", mProgInfo.iBytes, byteRate);
    mProgressInfo.setText(txt);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    // Check which request we're responding to
    if (requestCode == FILE_ACTIVITY_REQ) {
      // Make sure the request was successful
      if (resultCode == RESULT_OK) {
        String filename = data.getStringExtra(FileActivity.EXTRA_FILENAME);
        loadFile(filename, false);
      }
    }
  }

  /*
   * Called when a notification with the current image info has been received
   */

  private void programBlock() {
  	if (!mProgramming)
  		return;
  	
    if (mProgInfo.iBlocks < mProgInfo.nBlocks) {
      mProgramming = true;

      // Prepare block
      mOadBuffer[0] = Conversion.loUint16(mProgInfo.iBlocks);
      mOadBuffer[1] = Conversion.hiUint16(mProgInfo.iBlocks);
      System.arraycopy(mFileBuffer, mProgInfo.iBytes, mOadBuffer, 2, OAD_BLOCK_SIZE);

      // Send block
      mBLEUtil.send(MooshimeterDevice.mUUID.OAD_IMAGE_BLOCK, mOadBuffer, new BLEUtil.BLEUtilCB() {
          @Override
          public void run() {
              if (error == BluetoothGatt.GATT_SUCCESS) {
                  // Update stats
                  mProgInfo.iBlocks++;
                  mProgInfo.iBytes += OAD_BLOCK_SIZE;
                  mProgressBar.setProgress((mProgInfo.iBlocks * 100) / mProgInfo.nBlocks);
                  programBlock();
              } else {
                  mProgramming = false;
                  mLog.append("GATT writeCharacteristic failed\n");
              }
          }
      });
    } else {
      mProgramming = false;
    }

    if (!mProgramming) {
      runOnUiThread(new Runnable() {
        public void run() {
          displayStats();
          stopProgramming();
        }
      });
    }
  }

	private class ProgTimerTask extends TimerTask {
    @Override
    public void run() {
      mProgInfo.iTimeElapsed += TIMER_INTERVAL;
    }
  }

    private class ImgHdr {
        short crc0;
        short crc1;
        short ver;
        int len;
        int build_time;
        byte[] res = new byte[4];
        public void unpack(byte[] fbuf) {
            ByteBuffer b = ByteBuffer.wrap(fbuf);
            b.order(ByteOrder.LITTLE_ENDIAN);
            crc0        = b.getShort();
            crc1        = b.getShort();
            ver         = b.getShort();
            len         = 0xFFFF&((int)b.getShort());
            build_time  = b.getInt();
            for(int i=0;i<4;i++) {
                res[i] = b.get();
            }
        }
        public byte[] pack() {
            byte[] retval = new byte[16];
            ByteBuffer b = ByteBuffer.wrap(retval);
            b.order(ByteOrder.LITTLE_ENDIAN);
            b.putShort(crc0);
            b.putShort(crc1);
            b.putShort(ver);
            b.putShort((short)len);
            b.putInt(build_time);
            for(int i=0;i<4;i++) {
                b.put(res[i]);
            }
            return retval;
        }
    }

  private class ProgInfo {
    int iBytes = 0; // Number of bytes programmed
    short iBlocks = 0; // Number of blocks programmed
    short nBlocks = 0; // Total number of blocks
    int iTimeElapsed = 0; // Time elapsed in milliseconds

    void reset() {
      iBytes = 0;
      iBlocks = 0;
      iTimeElapsed = 0;
      nBlocks = (short) (mFileImgHdr.len / (OAD_BLOCK_SIZE / HAL_FLASH_WORD_SIZE));
    }
  }

}
