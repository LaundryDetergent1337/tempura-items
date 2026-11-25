    // Add these 3 reusable fields inside YoloDetectorHelper right below inputSize = 640
    private val totalPixels = inputSize * inputSize
    private val reusableBuffer = ByteBuffer.allocateDirect(4 * 3 * totalPixels).apply {
        order(ByteOrder.nativeOrder())
    }
    private val reusablePixels = IntArray(totalPixels)
    private var reusableOutput: Array<Array<FloatArray>>? = null