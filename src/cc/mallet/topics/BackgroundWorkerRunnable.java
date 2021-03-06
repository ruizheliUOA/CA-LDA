package cc.mallet.topics;

import cc.mallet.types.FeatureSequence;
import cc.mallet.util.Randoms;

import java.util.ArrayList;
import java.util.Arrays;

public class BackgroundWorkerRunnable extends WorkerRunnable {
    double lambda;
    double betaBackground;
    int backgroundTopic = ParallelTopicModel.UNASSIGNED_TOPIC;
    int TOPICAL_WORD_INDEX = BackgroundTopicModel.TOPICAL_WORD_INDEX;
    int BACKGROUND_WORD_INDEX = BackgroundTopicModel.BACKGROUND_WORD_INDEX;

    public int[] getTypeBackgroundCounts() {
        return typeBackgroundCounts;
    }

    public int[] getBackgroundAndTopicalCounts() {
        return backgroundAndTopicalCounts;
    }

    protected int[] typeBackgroundCounts; // indexed by <feature index>
    protected int[] backgroundAndTopicalCounts;

    public BackgroundWorkerRunnable(int numTopics, double[] alpha, double alphaSum, double beta, double betaBackground, double lambda,
                                    Randoms random, ArrayList<TopicAssignment> data,
                                    int[][] typeTopicCounts, int[] tokensPerTopic,
                                    int[] typeBackgroundCounts, int[] backgroundAndTopicalCounts,
                                    int startDoc, int numDocs) {
        super(numTopics, alpha, alphaSum, beta, random, data, typeTopicCounts, tokensPerTopic, startDoc, numDocs);
        this.lambda = lambda;
        this.betaBackground = betaBackground;
        this.typeBackgroundCounts = typeBackgroundCounts;
        this.backgroundAndTopicalCounts = backgroundAndTopicalCounts;

    }

    public void buildLocalTypeTopicCounts () {
        super.buildLocalTypeTopicCounts();
        Arrays.fill(typeBackgroundCounts, 0);
        Arrays.fill(backgroundAndTopicalCounts, 0);

        for (int doc = startDoc;
             doc < data.size() && doc < startDoc + numDocs;
             doc++) {

            TopicAssignment document = data.get(doc);

            FeatureSequence tokens = (FeatureSequence) document.instance.getData();
            FeatureSequence topicSequence =  (FeatureSequence) document.topicSequence;
            // build local typeBackgroundCounts and backgroundAndTopicalCounts
            int[] topics = topicSequence.getFeatures();
            for (int position = 0; position < tokens.size(); position++) {

                int topic = topics[position];

                if (topic == backgroundTopic) {
                    int type = tokens.getIndexAtPosition(position);
                    typeBackgroundCounts[type]++;
                    backgroundAndTopicalCounts[BACKGROUND_WORD_INDEX] ++;

                }else{
                    backgroundAndTopicalCounts[TOPICAL_WORD_INDEX] ++;
                }
            }
        }


    }
    protected double computeBackgroundCoeff(int token, int[] localBackgroundTopicCount){

        double coff = alphaSum + localBackgroundTopicCount[TOPICAL_WORD_INDEX];
        coff *=  (typeBackgroundCounts[token] + betaBackground);
        coff *=  (localBackgroundTopicCount[BACKGROUND_WORD_INDEX] + lambda );
        coff /=  (localBackgroundTopicCount[TOPICAL_WORD_INDEX] + lambda );
        coff /= ((betaBackground * numTypes) + backgroundAndTopicalCounts[BACKGROUND_WORD_INDEX] );



        return coff;
    }
    protected void sampleTopicsForOneDoc (FeatureSequence tokenSequence,
                                          FeatureSequence topicSequence,
                                          boolean readjustTopicsAndStats /* currently ignored */) {

        int[] oneDocTopics = topicSequence.getFeatures();

        int[] currentTypeTopicCounts;
        int type, oldTopic, newTopic;
        double topicWeightsSum;
        int docLength = tokenSequence.getLength();

        int[] localTopicCounts = new int[numTopics];
        int[] localTopicIndex = new int[numTopics];
        int[] localBackgroundTopicCount = new int[2];

        //		populate topic counts
        for (int position = 0; position < docLength; position++) {
            if (oneDocTopics[position] == backgroundTopic) { continue; }
            localTopicCounts[oneDocTopics[position]]++;
        }

        // Build an array that densely lists the topics that
        //  have non-zero counts.
        int denseIndex = 0;
        for (int topic = 0; topic < numTopics; topic++) {
            if (localTopicCounts[topic] != 0) {
                localTopicIndex[denseIndex] = topic;
                denseIndex++;
            }
        }

        // populate local B/T counts
        for (int position = 0; position < docLength; position++) {
            if (oneDocTopics[position] == backgroundTopic) {
                localBackgroundTopicCount[BACKGROUND_WORD_INDEX]++;
            }else{
                localBackgroundTopicCount[TOPICAL_WORD_INDEX]++;
            }
        }

        // Record the total number of non-zero topics
        int nonZeroTopics = denseIndex;

        //		Initialize the topic count/beta sampling bucket
        double topicBetaMass = 0.0;

        // Initialize cached coefficients and the topic/beta
        //  normalizing constant.

        for (denseIndex = 0; denseIndex < nonZeroTopics; denseIndex++) {
            int topic = localTopicIndex[denseIndex];
            int n = localTopicCounts[topic];

            //	initialize the normalization constant for the (B * n_{t|d}) term
            topicBetaMass += beta * n /	(tokensPerTopic[topic] + betaSum);

            //	update the coefficients for the non-zero topics
            cachedCoefficients[topic] =	(alpha[topic] + n) / (tokensPerTopic[topic] + betaSum);
        }



        double topicTermMass = 0.0;

        double[] topicTermScores = new double[numTopics];
        int[] topicTermIndices;
        int[] topicTermValues;
        int i;
        double score;

        //	Iterate over the positions (words) in the document
        for (int position = 0; position < docLength; position++) {
            type = tokenSequence.getIndexAtPosition(position);
            oldTopic = oneDocTopics[position];

            currentTypeTopicCounts = typeTopicCounts[type];

            if (oldTopic != backgroundTopic) {
                //	Remove this token from all counts.

                // remove its contribution to localB/TCount
                localBackgroundTopicCount[TOPICAL_WORD_INDEX]--;
                backgroundAndTopicalCounts[TOPICAL_WORD_INDEX]--;

                // Remove this topic's contribution to the
                //  normalizing constants
                smoothingOnlyMass -= alpha[oldTopic] * beta /
                        (tokensPerTopic[oldTopic] + betaSum);
                topicBetaMass -= beta * localTopicCounts[oldTopic] /
                        (tokensPerTopic[oldTopic] + betaSum);

                // Decrement the local doc/topic counts

                localTopicCounts[oldTopic]--;

                // Maintain the dense index, if we are deleting
                //  the old topic
                if (localTopicCounts[oldTopic] == 0) {

                    // First get to the dense location associated with
                    //  the old topic.

                    denseIndex = 0;

                    // We know it's in there somewhere, so we don't
                    //  need bounds checking.
                    while (localTopicIndex[denseIndex] != oldTopic) {
                        denseIndex++;
                    }

                    // shift all remaining dense indices to the left.
                    while (denseIndex < nonZeroTopics) {
                        if (denseIndex < localTopicIndex.length - 1) {
                            localTopicIndex[denseIndex] =
                                    localTopicIndex[denseIndex + 1];
                        }
                        denseIndex++;
                    }

                    nonZeroTopics --;
                }

                // Decrement the global topic count totals
                tokensPerTopic[oldTopic]--;
                assert(tokensPerTopic[oldTopic] >= 0) : "old Topic " + oldTopic + " below 0";


                // Add the old topic's contribution back into the
                //  normalizing constants.
                smoothingOnlyMass += alpha[oldTopic] * beta /
                        (tokensPerTopic[oldTopic] + betaSum);
                topicBetaMass += beta * localTopicCounts[oldTopic] /
                        (tokensPerTopic[oldTopic] + betaSum);

                // Reset the cached coefficient for this topic
                cachedCoefficients[oldTopic] =
                        (alpha[oldTopic] + localTopicCounts[oldTopic]) /
                                (tokensPerTopic[oldTopic] + betaSum);
            }else{

                // remove its contribution to localB/TCount
                localBackgroundTopicCount[BACKGROUND_WORD_INDEX]--;
                backgroundAndTopicalCounts[BACKGROUND_WORD_INDEX]--;
                typeBackgroundCounts[type]--;

            }
            // compute the mass for background word
            double backgroundMass = computeBackgroundCoeff(type, localBackgroundTopicCount);

            // Now go over the type/topic counts, decrementing
            //  where appropriate, and calculating the score
            //  for each topic at the same time.

            int index = 0;
            int currentTopic, currentValue;

            boolean alreadyDecremented = (oldTopic == backgroundTopic);

            topicTermMass = 0.0;

            while (index < currentTypeTopicCounts.length &&
                    currentTypeTopicCounts[index] > 0) {
                currentTopic = currentTypeTopicCounts[index] & topicMask;
                currentValue = currentTypeTopicCounts[index] >> topicBits;

                if (! alreadyDecremented &&
                        currentTopic == oldTopic) {

                    // We're decrementing and adding up the
                    //  sampling weights at the same time, but
                    //  decrementing may require us to reorder
                    //  the topics, so after we're done here,
                    //  look at this cell in the array again.

                    currentValue --;
                    if (currentValue == 0) {
                        currentTypeTopicCounts[index] = 0;
                    }
                    else {
                        currentTypeTopicCounts[index] =
                                (currentValue << topicBits) + oldTopic;
                    }

                    // Shift the reduced value to the right, if necessary.

                    int subIndex = index;
                    while (subIndex < currentTypeTopicCounts.length - 1 &&
                            currentTypeTopicCounts[subIndex] < currentTypeTopicCounts[subIndex + 1]) {
                        int temp = currentTypeTopicCounts[subIndex];
                        currentTypeTopicCounts[subIndex] = currentTypeTopicCounts[subIndex + 1];
                        currentTypeTopicCounts[subIndex + 1] = temp;

                        subIndex++;
                    }

                    alreadyDecremented = true;
                }
                else {
                    score =
                            cachedCoefficients[currentTopic] * currentValue;
                    topicTermMass += score;
                    topicTermScores[index] = score;

                    index++;
                }
            }

            double sample = random.nextUniform() * (smoothingOnlyMass + topicBetaMass + topicTermMass + backgroundMass);

            //	Make sure it actually gets set
            newTopic = -1;

            if (sample < backgroundMass)             {
                newTopic = backgroundTopic;
            }else{
                sample -= backgroundMass;
                if (sample < topicTermMass) {
                    //topicTermCount++;

                    i = -1;
                    while (sample > 0) {
                        i++;
                        sample -= topicTermScores[i];
                    }

                    newTopic = currentTypeTopicCounts[i] & topicMask;
                    currentValue = currentTypeTopicCounts[i] >> topicBits;

                    currentTypeTopicCounts[i] = ((currentValue + 1) << topicBits) + newTopic;

                    // Bubble the new value up, if necessary

                    while (i > 0 &&
                            currentTypeTopicCounts[i] > currentTypeTopicCounts[i - 1]) {
                        int temp = currentTypeTopicCounts[i];
                        currentTypeTopicCounts[i] = currentTypeTopicCounts[i - 1];
                        currentTypeTopicCounts[i - 1] = temp;

                        i--;
                    }

                }
                else {
                    sample -= topicTermMass;

                    if (sample < topicBetaMass) {
                        //betaTopicCount++;

                        sample /= beta;

                        for (denseIndex = 0; denseIndex < nonZeroTopics; denseIndex++) {
                            int topic = localTopicIndex[denseIndex];

                            sample -= localTopicCounts[topic] /
                                    (tokensPerTopic[topic] + betaSum);

                            if (sample <= 0.0) {
                                newTopic = topic;
                                break;
                            }
                        }

                    }
                    else {
                        //smoothingOnlyCount++;

                        sample -= topicBetaMass;

                        sample /= beta;

                        newTopic = 0;
                        sample -= alpha[newTopic] /
                                (tokensPerTopic[newTopic] + betaSum);

                        while (sample > 0.0) {
                            newTopic++;
                            sample -= alpha[newTopic] /
                                    (tokensPerTopic[newTopic] + betaSum);
                        }

                    }

                    // Move to the position for the new topic,
                    //  which may be the first empty position if this
                    //  is a new topic for this word.

                    index = 0;
                    while (currentTypeTopicCounts[index] > 0 &&
                            (currentTypeTopicCounts[index] & topicMask) != newTopic) {
                        index++;
                        if (index == currentTypeTopicCounts.length) {
                            System.err.println("type: " + type + " new topic: " + newTopic);
                            for (int k=0; k<currentTypeTopicCounts.length; k++) {
                                System.err.print((currentTypeTopicCounts[k] & topicMask) + ":" +
                                        (currentTypeTopicCounts[k] >> topicBits) + " ");
                            }
                            System.err.println();

                        }
                    }


                    // index should now be set to the position of the new topic,
                    //  which may be an empty cell at the end of the list.

                    if (currentTypeTopicCounts[index] == 0) {
                        // inserting a new topic, guaranteed to be in
                        //  order w.r.t. count, if not topic.
                        currentTypeTopicCounts[index] = (1 << topicBits) + newTopic;
                    }
                    else {
                        currentValue = currentTypeTopicCounts[index] >> topicBits;
                        currentTypeTopicCounts[index] = ((currentValue + 1) << topicBits) + newTopic;

                        // Bubble the increased value left, if necessary
                        while (index > 0 &&
                                currentTypeTopicCounts[index] > currentTypeTopicCounts[index - 1]) {
                            int temp = currentTypeTopicCounts[index];
                            currentTypeTopicCounts[index] = currentTypeTopicCounts[index - 1];
                            currentTypeTopicCounts[index - 1] = temp;

                            index--;
                        }
                    }

                }

            }

            oneDocTopics[position] = newTopic;

            if (newTopic == backgroundTopic) {

                backgroundAndTopicalCounts[BACKGROUND_WORD_INDEX] ++;
                localBackgroundTopicCount[BACKGROUND_WORD_INDEX] ++;
                typeBackgroundCounts[type] ++;
            }else {
                backgroundAndTopicalCounts[TOPICAL_WORD_INDEX] ++;
                localBackgroundTopicCount[TOPICAL_WORD_INDEX] ++;
                //assert(newTopic != -1);

                //	Put that new topic into the counts


                smoothingOnlyMass -= alpha[newTopic] * beta /
                        (tokensPerTopic[newTopic] + betaSum);
                topicBetaMass -= beta * localTopicCounts[newTopic] /
                        (tokensPerTopic[newTopic] + betaSum);

                localTopicCounts[newTopic]++;

                // If this is a new topic for this document,
                //  add the topic to the dense index.
                if (localTopicCounts[newTopic] == 1) {

                    // First find the point where we
                    //  should insert the new topic by going to
                    //  the end (which is the only reason we're keeping
                    //  track of the number of non-zero
                    //  topics) and working backwards

                    denseIndex = nonZeroTopics;

                    while (denseIndex > 0 &&
                            localTopicIndex[denseIndex - 1] > newTopic) {

                        localTopicIndex[denseIndex] =
                                localTopicIndex[denseIndex - 1];
                        denseIndex--;
                    }

                    localTopicIndex[denseIndex] = newTopic;
                    nonZeroTopics++;
                }

                tokensPerTopic[newTopic]++;

                //	update the coefficients for the non-zero topics
                cachedCoefficients[newTopic] =
                        (alpha[newTopic] + localTopicCounts[newTopic]) /
                                (tokensPerTopic[newTopic] + betaSum);

                smoothingOnlyMass += alpha[newTopic] * beta /
                        (tokensPerTopic[newTopic] + betaSum);
                topicBetaMass += beta * localTopicCounts[newTopic] /
                        (tokensPerTopic[newTopic] + betaSum);
            }

        }

        if (shouldSaveState) {
            // Update the document-topic count histogram,
            //  for dirichlet estimation
            docLengthCounts[ docLength ]++;

            for (denseIndex = 0; denseIndex < nonZeroTopics; denseIndex++) {
                int topic = localTopicIndex[denseIndex];

                topicDocCounts[topic][ localTopicCounts[topic] ]++;
            }
        }

        //	Clean up our mess: reset the coefficients to values with only
        //	smoothing. The next doc will update its own non-zero topics...

        for (denseIndex = 0; denseIndex < nonZeroTopics; denseIndex++) {
            int topic = localTopicIndex[denseIndex];

            cachedCoefficients[topic] =
                    alpha[topic] / (tokensPerTopic[topic] + betaSum);
        }

    }




}
