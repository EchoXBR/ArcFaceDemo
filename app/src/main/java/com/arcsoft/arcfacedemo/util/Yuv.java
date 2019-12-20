package com.arcsoft.arcfacedemo.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.io.ByteArrayOutputStream;
public class Yuv{
	public static void YV12Resize(byte[] pSrc, Point szSrc, byte[] pDst, Point szDst){
		int srcPitchY=szSrc.x,srcPitchUV=szSrc.x/2,dstPitchY=szDst.x,dstPitchUV=szDst.x/2;
		
		int rateX=(szSrc.x<<16)/szDst.x;
		int rateY=(szSrc.y<<16)/szDst.y;
		for (int i=0;i<szDst.y;i++)
		{
			int srcY=i*rateY>>16;
	
			for (int j=0;j<szDst.x;j++)
			{
				int srcX=j*rateX>>16;
				pDst[dstPitchY * i + j] = pSrc[srcY*srcPitchY + srcX];//*(pSrcYLine+srcX);
			}
		}
		for (int i=0;i<szDst.y/2;i++)
		{
			int srcY=i*rateY>>16;
			
			for (int j=0;j<szDst.x/2;j++)
			{
				int srcX=j*rateX>>16;

				pDst[dstPitchY*szDst.y+i*dstPitchUV + j] = pSrc[srcPitchY*szSrc.y+srcY*srcPitchUV + srcX];//*(pSrcVLine+srcX);
				pDst[dstPitchY*szDst.y+i*dstPitchUV + dstPitchUV*szDst.y/2 + j]= pSrc[srcPitchY*szSrc.y+srcY*srcPitchUV + srcPitchUV*szSrc.y/2 + srcX];
	
			}
		}
	}
	
	
	
	
	// eg.
//	private void test(){
//		byte[] resizeYV12 = new byte[destWidth * destHeight * 3 / 2];
//		Point pointSrc = new Point(mSize.width, mSize.height);
//		try {
//			YV12Resize(mData, pointSrc, resizeYV12, new Point(destWidth, destHeight));
//		} catch (Exception e) {
//			// TODO: handle exception
//			return null;
//		}
//	}
	
	
	
	
	// YV12 To NV21
    public static void YV12toNV21(final byte[] input, final byte[] output, final int width, final int height) {
    	//long startMs = System.currentTimeMillis();
        final int frameSize = width * height;
        final int qFrameSize = frameSize / 4;
        final int tempFrameSize = frameSize * 5 / 4;
        
        System.arraycopy(input, 0, output, 0, frameSize); // Y

        for (int i = 0; i < qFrameSize; i++) {
        	output[frameSize + i * 2] = input[frameSize + i]; // Cb (U)
            output[frameSize + i * 2 + 1] = input[tempFrameSize + i]; // Cr (V)
        }
    }
    
    //I420 To NV21
	public static void I420ToNV21(final byte[] input, final byte[] output, final int width, final int height) {
    	//long startMs = System.currentTimeMillis();
        final int frameSize = width * height;
        final int qFrameSize = frameSize / 4;
        final int tempFrameSize = frameSize * 5 / 4;
        
        System.arraycopy(input, 0, output, 0, frameSize); // Y

        for (int i = 0; i < qFrameSize; i++) {
        	output[frameSize + i * 2] = input[tempFrameSize + i]; // Cb (U)
            output[frameSize + i * 2 + 1] = input[frameSize + i]; // Cr (V)
        }
    }
	
	public static Bitmap NV21ToBitmap(byte[] data, int previewWidth, int previewHeight) {
        YuvImage yuvimage = new YuvImage(
                data,
                ImageFormat.NV21,
                previewWidth,
                previewHeight,
                null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvimage.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), 100, baos);// 80--JPG图片的质量[0-100],100最高
        byte[] rawImage = baos.toByteArray();
        //将rawImage转换成bitmap
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length, options);
        return bitmap;
    }
	
    public static byte[] flipYUV420(byte[] data, int imageWidth, int imageHeight) {
        byte[] yuv = new byte[imageWidth * imageHeight * 3 / 2];
        int i;
        int count = 0;

        for (i = imageWidth * imageHeight - 1; i >= 0; i--) {
            yuv[count] = data[i];
            count++;
        }

        for (i = imageWidth * imageHeight * 3 / 2 - 1; i >= imageWidth
                * imageHeight; i -= 2) {
            yuv[count++] = data[i - 1];
            yuv[count++] = data[i];
        }
        return yuv;
    }

    //yv12 转 yuv420p  yvu -> yuv
    public static  void swapYV12toI420(byte[] yv12bytes, byte[] i420bytes, int width, int height)
    {
        System.arraycopy(yv12bytes, 0, i420bytes, 0,width*height);
        System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes, width*height,width*height/4);
        System.arraycopy(yv12bytes, width*height, i420bytes, width*height+width*height/4,width*height/4);
    }

    public static byte[] swapYV12toI420(byte[] yv12bytes, int width, int height) {
        byte[] i420bytes = new byte[yv12bytes.length];
        for (int i = 0; i < width*height; i++) {
            i420bytes[i] = yv12bytes[i];
        }
        for (int i = width*height; i < width*height + (width/2*height/2); i++) {
            i420bytes[i] = yv12bytes[i + (width/2*height/2)];
        }
        for (int i = width*height + (width/2*height/2); i < width*height + 2*(width/2*height/2); i++) {
            i420bytes[i] = yv12bytes[i - (width/2*height/2)];
        }
        return i420bytes;
    }
}