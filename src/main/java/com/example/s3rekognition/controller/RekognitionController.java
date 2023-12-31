package com.example.s3rekognition.controller;

import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.example.s3rekognition.PPEClassificationResponse;
import com.example.s3rekognition.PPEResponse;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.beans.factory.annotation.Autowired;


import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

//Referenser:
//https://bootcamptoprod.com/measure-api-response-time-spring-boot/
//https://www.javadoc.io/doc/io.micrometer/micrometer-core/1.0.3/io/micrometer/core/instrument/Timer.html#mean-java.util.concurrent.TimeUnit-
//https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ResponseEntity.html


@RestController
public class RekognitionController implements ApplicationListener<ApplicationReadyEvent> {

    private final AmazonS3 s3Client;
    private final AmazonRekognition rekognitionClient;
    
    private final Timer responseTime;
    private final Counter countImage;
    private final DistributionSummary totalviolation;
    private final MeterRegistry meterRegistry; 
    

    private static final Logger logger = Logger.getLogger(RekognitionController.class.getName());
    
    @Autowired
    public RekognitionController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.s3Client = AmazonS3ClientBuilder.standard().build();
        this.rekognitionClient = AmazonRekognitionClientBuilder.standard().build();

        
        // Initializing the metrics
        this.responseTime = this.meterRegistry.timer("response_time");
        this.countImage = this.meterRegistry.counter("count_image");
        this.totalviolation = this.meterRegistry.summary("total_violation");
    }
    
    @GetMapping(value = "/avg-response-time")
    public ResponseEntity<String> getResponseTime() {
        double avgResponseTime = responseTime.mean(TimeUnit.MILLISECONDS);
        return ResponseEntity.ok("The average response time: " + avgResponseTime + " millisecond.");
    }
    
    @GetMapping(value = "/count-image")
    public ResponseEntity<String> getImageCount() {
        return ResponseEntity.ok("Total images thats processed: " + countImage.count());
    }
    
    @GetMapping(value = "/total-violation")
    public ResponseEntity<String> getTotalViolation() {
        return ResponseEntity.ok("Total of violations: " + totalviolation.totalAmount());
    }
    

    /**
     * This endpoint takes an S3 bucket name in as an argument, scans all the
     * Files in the bucket for Protective Gear Violations.
     * <p>
     *
     * @param bucketName
     * @return
     */
    @GetMapping(value = "/scan-ppe", consumes = "*/*", produces = "application/json")
    @ResponseBody
    public ResponseEntity<PPEResponse> scanForPPE(@RequestParam String bucketName) {
        //Start the timer for response time
        Timer.Sample sampleTimer = Timer.start(meterRegistry);
        
        // List all objects in the S3 bucket
        ListObjectsV2Result imageList = s3Client.listObjectsV2(bucketName);

        // This will hold all of our classifications
        List<PPEClassificationResponse> classificationResponses = new ArrayList<>();

        // This is all the images in the bucket
        List<S3ObjectSummary> images = imageList.getObjectSummaries();
        
         // Increment the image counter for each image in the bucket
        countImage.increment(images.size());

        // Iterate over each object and scan for PPE
        for (S3ObjectSummary image : images) {
            logger.info("scanning " + image.getKey());

            // This is where the magic happens, use AWS rekognition to detect PPE
            DetectProtectiveEquipmentRequest request = new DetectProtectiveEquipmentRequest()
                    .withImage(new Image()
                            .withS3Object(new S3Object()
                                    .withBucket(bucketName)
                                    .withName(image.getKey())))
                    .withSummarizationAttributes(new ProtectiveEquipmentSummarizationAttributes()
                            .withMinConfidence(80f)
                            .withRequiredEquipmentTypes("FACE_COVER"));

            DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);

            // If any person on an image lacks PPE on the face, it's a violation of regulations
            boolean violation = isViolation(result);

            logger.info("scanning " + image.getKey() + ", violation result " + violation);
            // Categorize the current image as a violation or not.
            int personCount = result.getPersons().size();
            PPEClassificationResponse classification = new PPEClassificationResponse(image.getKey(), personCount, violation);
            classificationResponses.add(classification);
        }
        
        // Record the number of violations found
        long countViolations = classificationResponses.stream()
                .filter(PPEClassificationResponse -> PPEClassificationResponse.isViolation())
                .count();
        totalviolation.record(countViolations);

        // Stop the timer and record the time
        sampleTimer.stop(responseTime);
        
        PPEResponse ppeResponse = new PPEResponse(bucketName, classificationResponses);
        return ResponseEntity.ok(ppeResponse);
    }

    /**
     * Detects if the image has a protective gear violation for the FACE bodypart-
     * It does so by iterating over all persons in a picture, and then again over
     * each body part of the person. If the body part is a FACE and there is no
     * protective gear on it, a violation is recorded for the picture.
     *
     * @param result
     * @return
     */
    private static boolean isViolation(DetectProtectiveEquipmentResult result) {
        return result.getPersons().stream()
                .flatMap(p -> p.getBodyParts().stream())
                .anyMatch(bodyPart -> bodyPart.getName().equals("FACE")
                        && bodyPart.getEquipmentDetections().isEmpty());
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {

    }
}