package org.bof.geometric;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.imglib2.ExtendedRandomAccessibleInterval;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.MixedTransformView;
import net.imglib2.view.Views;

import org.knime.core.node.KNIMEConstants;
import org.knime.knip.base.KNIPConstants;
import org.knime.knip.base.ThreadPoolExecutorService;
import org.knime.knip.opencv.base.algorithm.AlgorithmParameter;
import org.knime.knip.opencv.base.descriptor.AbstractDescriptor;
import org.opencv.features2d.KeyPoint;

/**
 * @param <T>
 */
public class GeometricBlurDescriptor<T extends RealType<T> & NativeType<T>>
		extends AbstractDescriptor<T> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public GeometricBlurDescriptor() {
		super(-1, new AlgorithmParameter<>("Max Sigma", "maxsigma", "maxsigma",
				10), new AlgorithmParameter<>("Min Sigma", "minsigma",
				"minsigma", 2), new AlgorithmParameter<>("Blur Levels",
				"blurlevels", "blurlevels", 5));
	}

	@Override
	public String[] getColumnHeaders() {
		// we use ((4*12)+1)*#channels
		// 4 sample rings with 12 samples per ring plus the keypoint itself
		// and then append each edge channel. we want to go for four edge channels
		// like in the paper
		// therefore we get a descriptor size of: ((4 * 12) + 1) * 4 = 196
		String[] columnHeaders = new String[196];
		for (int i = 1; i <= columnHeaders.length; i++) {
			columnHeaders[i - 1] = "Descriptor Value #" + i;
		}

		return columnHeaders;
	}

	private int[][] getSampleCoordinates( int radius ){
		// coordinates[0][?] := x
		// coordinates[1][?] := y
		int[][] coordinates = new int[2][12];
				
		double samplingAngle = 30;
		
		//sampling will start at the top of the circle clockwise		
		//top right
		// first sample
		coordinates[0][0] = 0;		
		coordinates[1][0] = radius;
		//second sample
		coordinates[0][1] = (int)Math.rint( Math.cos((samplingAngle * 2 * Math.PI)/180) * radius );		
		coordinates[1][1] = (int)Math.rint( Math.sin((samplingAngle * 2 * Math.PI)/180) * radius );
		//third sample
		coordinates[0][2] = (int)Math.rint( Math.cos((samplingAngle * Math.PI)/180) * radius );		
		coordinates[1][2] = (int)Math.rint( Math.sin((samplingAngle * Math.PI)/180) * radius );
		//fourth sample
		coordinates[0][3] = radius;		
		coordinates[1][3] = 0;
		//now we just mirror the samples
		//bottom right
		coordinates[0][4] = coordinates[0][2];		
		coordinates[1][4] = -coordinates[1][2];
		coordinates[0][5] = coordinates[0][1];		
		coordinates[1][5] = -coordinates[1][1];
		coordinates[0][6] = coordinates[0][0];		
		coordinates[1][6] = -coordinates[1][0];
		//bottom left
		coordinates[0][7] = -coordinates[0][5];		
		coordinates[1][7] = coordinates[1][5];
		coordinates[0][8] = -coordinates[0][4];		
		coordinates[1][8] = coordinates[1][4];
		coordinates[0][9] = -coordinates[0][3];		
		coordinates[1][9] = coordinates[1][3];
		//top left
		coordinates[0][10] = coordinates[0][8];		
		coordinates[1][10] = -coordinates[1][8];
		coordinates[0][11] = coordinates[0][7];		
		coordinates[1][11] = -coordinates[1][7];
		
		return coordinates;
	}
	
	// for each keypoint and the given input image (blurred image) extract the
	// geometric blur descriptor
	@Override
	public double[][] getDescriptorsForKeypoints(Img<T> img, List<KeyPoint> list) {
		//System.out.println("next");

		// Create the pyramid of images here
		// using linear sigmas

		// you can get the parameters with getParameters() and looping through
		// the parameters with checking for name
		
		

//		double minSigma = 2.0d;
//		double maxSigma = 10.0d;
//		int blurLevels = 5;
		double minSigma = 0.0;
		double maxSigma = 0.0;
		int blurLevels = 0;
		
		for(AlgorithmParameter<?> param : getParameters()){
			if(param.getName() == "Max Sigma"){
				maxSigma = new Double(param.getValueAsString());
			}
			else if(param.getName() == "Min Sigma"){
				minSigma = new Double(param.getValueAsString());
			}
			else if(param.getName() == "Blur Levels"){
				blurLevels = new Integer(param.getValueAsString());
			}
		}
		
		

		// yeah
		double stepSize = (maxSigma - minSigma) / blurLevels;

		long[] newDims = new long[] { img.dimension(0), img.dimension(1),
				img.dimension(2), blurLevels };

		ArrayImg<DoubleType, ?> tmpImg = new ArrayImgFactory<DoubleType>()
				.create(newDims, new DoubleType());

		ThreadPoolExecutorService pool = new ThreadPoolExecutorService(
	            KNIMEConstants.GLOBAL_THREAD_POOL.createSubPool(KNIPConstants.THREADS_PER_NODE));
		for (int l = 0; l < blurLevels; l++) {
			// Do that for each "edge filter"
			for (int c = 0; c < img.dimension(2); c++) {
				try {
				    
//					IntervalView<T> temp1 = Views.interval(img,
//							new long[] { 0, 0, 0 }, new long[] {
//							newDims[0] - 1, newDims[1] - 1, c });
					
					//IntervalView<T> tempInput = Views.hyperSlice(img, 2, c);
					
					// mirror the image so theres no border
					ExtendedRandomAccessibleInterval<T, IntervalView<T>> inputPlane = Views.extendMirrorSingle(Views.hyperSlice(img, 2, c));
					
					// add 4th dimension
//					MixedTransformView<T> currentImg = Views.addDimension(temp2);
					
					// Output-Plane
					IntervalView<DoubleType> outputPlane = Views.hyperSlice(Views.hyperSlice(tmpImg, 3, l), 2, c);
					
					double[] sigmas = new double[outputPlane.numDimensions()];
					Arrays.fill(sigmas, minSigma + (l * stepSize));
					
					Gauss3.gauss(sigmas, inputPlane, outputPlane, pool);
				} catch (IncompatibleTypeException e) {
					e.printStackTrace();
				}
			}
		}

		double[][] data = new double[list.size()][196];

		// index of current keypoint 
		int currentKeyPoint = 0;
		
		// we assume that we only have one set of keypoints for an whole image instead of
		// a set of keypoints for every edge channel
		// we also assume that we only have four edge channels respectively we only use the
		// first four channels
		
		
		final RandomAccess<DoubleType> rndAccess = Views.extendMirrorSingle(tmpImg).randomAccess();
				
		for (final KeyPoint key : list) {
		
		// position in the descriptor (column of data array)
		int arrayCurser = 0;
			
			// loop channels
			for ( int i = 0 ; i < 4 ; i++ ){
				//set channel
				rndAccess.setPosition(i, 2);
				//set x position
				rndAccess.setPosition((int) key.pt.x, 0);
				//set y position
				rndAccess.setPosition((int) key.pt.y, 1);
				//set blurlvl
				rndAccess.setPosition(0,3);
				
				
				data[currentKeyPoint][arrayCurser] = rndAccess.get().get();
				arrayCurser++;
				
				
				int blurLvl = 1;
				// sample ring patterns 
				// we sample four rings in steps of 5 pixels(?) around the keypoint 
				for ( int j = 5 ; j <= 35 ; j = j+10 ){
					int[][] coordinates = getSampleCoordinates(j);
					
					// get the samples from the calculated coordinates
					for ( int v = 0 ; v < coordinates[0].length ; v++ ){
						// set blurLVL
						rndAccess.setPosition(blurLvl,3);
						//x						
						rndAccess.setPosition(coordinates[0][v] + (int)key.pt.x, 0);
						//y						
						rndAccess.setPosition(coordinates[1][v] + (int)key.pt.y, 1);
						data[currentKeyPoint][arrayCurser] = rndAccess.get().get();
						arrayCurser++;
					}
					blurLvl++;
				}
			}
			
			
			
			

			// Unfortunately we support only 2D since now. In our case we need
			// the channel (Edge) information. We use another field in the
			// keypoint ( octave is the channel number )
			
			// rndAccess.setPosition(key.octave, 2);
			
			// now we have set the random access to the center point on or image
			// from where it was extracted.
			// you guys have to implement the function which samples around the
			// point some other points (as in the paper).
			// use rndAccess.setPosition(X value, 0)
			// use rndAccess.setPosition(Y value, 1) to do so..

			// to access the different gauss levels use
			// rndAccess.setPosition(GAUSS LEVEL,3)
			
			// use rndAccess.get() (returns double because the rndAccess is specified 
			// as double above) to access the pixel the randomAccess is pointing at
			
			currentKeyPoint++;
		}

		return data;
	}
}
