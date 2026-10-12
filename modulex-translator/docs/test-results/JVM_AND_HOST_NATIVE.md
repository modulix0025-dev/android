# Test results (host run, 2026-10-12)

Command: `./gradlew :core:test :engines:test :signaling:test --rerun-tasks`
Host: Intel Xeon 2.80 GHz, 4 vCPU, Linux x86_64, OpenJDK 21. Raw JUnit XML is regenerated under `*/build/test-results/test/`.

**Totals: 96 passed, 0 failed, 1 skipped** (skipped: real-model corpus test, needs `-Pmodulex.testModel`).

## :core

| Suite | Test | Result | Time (s) |
|---|---|---|---|
| AssetsTest | productionSourceRefusesPlainHttp | PASSED | 0.119 |
| AssetsTest | insufficientStorageIsReportedBeforeDownloading | PASSED | 0.118 |
| AssetsTest | manifestValidationRejectsUnsafeEntries | PASSED | 0.068 |
| AssetsTest | interruptedDownloadResumesWithRange | PASSED | 0.179 |
| AssetsTest | installsVerifiesAndReportsStatus | PASSED | 0.106 |
| AssetsTest | importChecksPinnedHashOrRecordsUserImport | PASSED | 0.089 |
| AssetsTest | corruptionAfterInstallIsDetected | PASSED | 0.091 |
| AssetsTest | packsInUseCannotBeDeletedOrReplaced | PASSED | 0.062 |
| AssetsTest | corruptedDownloadIsRejectedAndNotMarkedInstalled | PASSED | 0.079 |
| AssetsTest | unpinnedPacksAreNeverDownloaded | PASSED | 0.023 |
| AssetsTest | ggufHeaderIsParsed | PASSED | 0.036 |
| ShippedManifestTest | shippedManifestIsValidAndHonest | PASSED | 0.035 |
| ConversationSessionTest | wrongSecretIsRejectedByHost | PASSED | 1.037 |
| ConversationSessionTest | handshakeAuthenticatesAndDeliversBothDirections | PASSED | 0.604 |
| ConversationSessionTest | aDifferentGuestCannotTakeOverAnEstablishedSession | PASSED | 0.178 |
| ConversationSessionTest | languageUpdatesPropagate | PASSED | 0.103 |
| ConversationSessionTest | reconnectReplaysUnacknowledgedMessagesWithoutDuplicates | PASSED | 0.162 |
| LoopbackLatencyTest | deliveryLatencyOverLoopbackTls | PASSED | 0.451 |
| LoopbackLatencyTest | output | `LOOPBACK (host JVM, not a device): connect+TLS+auth=36.8 ms; delivery median=1.055 ms p95=5.027 ms max=19.351 ms n=200` | |
| LanguageTest | thermalPolicyReducesWork | PASSED | 0.003 |
| LanguageTest | evictionKeepsTranslationResidentWhenPossible | PASSED | 0.003 |
| LanguageTest | uniqueScriptDetectionNeverGuessesSharedScripts | PASSED | 0.009 |
| LanguageTest | readinessRequiresInstalledVerifiedPacks | PASSED | 0.005 |
| LanguageTest | registryIsConsistent | PASSED | 0.0 |
| LanguageTest | scriptDetectionPicksSideOfPairOnlyWhenReliable | PASSED | 0.002 |
| ProtocolTest | codecRejectsMalformedUnknownAndOversized | PASSED | 0.011 |
| ProtocolTest | codecRoundTripsUnicode | PASSED | 0.001 |
| ProtocolTest | reliableTypesAreSequenced | PASSED | 0.0 |
| ProtocolTest | validatorAcceptsWellFormedMessages | PASSED | 0.0 |
| ProtocolTest | validatorRejectsBadMessages | PASSED | 0.001 |
| ReliabilityTest | recentIdsIsBounded | PASSED | 0.003 |
| ReliabilityTest | sequencerDeliversInOrderExactlyOnce | PASSED | 0.003 |
| ReliabilityTest | retriesThenGivesUp | PASSED | 0.001 |
| ReliabilityTest | queueAssignsSequenceAndAcknowledgesCumulatively | PASSED | 0.001 |
| PairingTest | shortAuthStringIsSixDigitsAndStable | PASSED | 0.001 |
| PairingTest | proofsBindSessionParticipantsAndFingerprints | PASSED | 0.0 |
| PairingTest | rejectsExpiredForeignAndIncompletePayloads | PASSED | 0.009 |
| PairingTest | qrPayloadRoundTrips | PASSED | 0.004 |
| PairingTest | pairingCodesNormaliseAndDeriveDeterministically | PASSED | 0.481 |
| PairingTest | idsAreUniqueAndUrlSafe | PASSED | 0.015 |
| SpeechTest | twoPhrasesBecomeTwoUtterances | PASSED | 0.017 |
| SpeechTest | ignoresBackgroundNoiseAndShortClicks | PASSED | 0.004 |
| SpeechTest | pushToTalkFlushEndsUtterance | PASSED | 0.002 |
| SpeechTest | detectsOneUtteranceSurroundedBySilence | PASSED | 0.005 |
| SpeechTest | longSpeechIsForceSplitAndBounded | PASSED | 0.012 |
| SpeechTest | overlapMergeRemovesDuplicatedBoundaryWords | PASSED | 0.002 |
| SpeechTest | nonSpeechMarkersAreDetected | PASSED | 0.0 |
| TranslationTest | sanitizerStripsThinkingBlocksOnly | PASSED | 0.002 |
| TranslationTest | paragraphsAndLayoutArePreserved | PASSED | 0.014 |
| TranslationTest | promptUsesDocumentedTemplates | PASSED | 0.007 |
| TranslationTest | longParagraphSplitsAtSentenceBoundariesWithinLimit | PASSED | 0.007 |
| TranslationTest | joinUsesTranslatedPiecesAndOriginalSeparators | PASSED | 0.0 |
| TranslationTest | unpunctuatedTextFallsBackToWordsWithoutBreakingSurrogates | PASSED | 0.02 |
| TranslationTest | arabicAndCjkPunctuationAreBoundaries | PASSED | 0.004 |
| TranslationTest | blankInputHasNoPieces | PASSED | 0.001 |
| TranslationTest | shortTextIsOnePiece | PASSED | 0.0 |
| TlsTransportTest | framingRejectsOversizedAndTruncatedFrames | PASSED | 0.069 |
| TlsTransportTest | wrongPinnedFingerprintIsRejected | PASSED | 0.132 |
| TlsTransportTest | tlsOverRealLoopbackTcp | PASSED | 0.138 |
| TlsTransportTest | handshakeOverPipesExchangesFramesBothWays | PASSED | 0.249 |
| TlsTransportTest | guestWithoutClientCertificateIsRejected | PASSED | 0.094 |

## :engines

| Suite | Test | Result | Time (s) |
|---|---|---|---|
| OcrEngineTest | blankImageYieldsNoText | PASSED | 0.296 |
| OcrEngineTest | recognisesArabicText | PASSED | 0.441 |
| OcrEngineTest | invalidInputsAreRejectedWithoutCrashing | PASSED | 0.116 |
| OcrEngineTest | recognisesMixedArabicEnglishPage | PASSED | 0.525 |
| OcrEngineTest | recognisesEnglishText | PASSED | 0.205 |
| OcrEngineTest | output | `OCR ara: raw-cer=0.660 pipeline-cer=0.000 scale=0.50 passes=3 conf=92.1 time=162ms text= مرحبا بكم فيالمطعم |  الرجاء الانتظار حتى يتم تقديمالطعام` | |
| OcrEngineTest | output | `OCR mixed: cer=0.261 scale=0.50 conf=95.2 time=311ms text=Exit |  مخرجالطوارئ | Pharmacy open 24 hours` | |
| OcrEngineTest | output | `known limitation reproduced: Arabic line 'صيدلية مفتوحة' not recognised` | |
| OcrEngineTest | output | `OCR eng: cer=0.000 conf=96.4 time=91ms text=The quick brown fox jumps over the lazy dog. | Platform 4 departs at 10:35 for Cairo.` | |
| OcrPipelineHelpersTest | diacriticGapsAreMergedIntoOneLine | PASSED | 0.009 |
| OcrPipelineHelpersTest | resizeAveragesAndGrayConversionWeightsChannels | PASSED | 0.003 |
| OcrPipelineHelpersTest | blankPageHasNoBands | PASSED | 0.003 |
| OcrPipelineHelpersTest | bidiMarksAreRemoved | PASSED | 0.0 |
| OcrPipelineHelpersTest | lineBandsFindEachLineWithPadding | PASSED | 0.01 |
| TranslationEngineTest | rejectsNonGgufAndWrongArchitectureBeforeNativeLoad | PASSED | 0.037 |
| TranslationEngineTest | realModelTranslatesCorpus | SKIPPED | 0.007 |
| TranslationEngineTest | nativeLoaderRejectsTruncatedModelWithoutCrashing | PASSED | 0.009 |
| TranslationEngineTest | pipelineRunsEndToEndWithFixtureModel | PASSED | 0.15 |

## :signaling

| Suite | Test | Result | Time (s) |
|---|---|---|---|
| RelayTest | joinRateLimitPerIp | PASSED | 2.402 |
| RelayTest | serverFull | PASSED | 0.132 |
| RelayTest | twoPeersJoin | PASSED | 0.04 |
| RelayTest | thirdParticipantGetsRoomFull | PASSED | 0.047 |
| RelayTest | badFramesThenClose | PASSED | 0.04 |
| RelayTest | binaryFrameRejected | PASSED | 0.025 |
| RelayTest | joinRateLimitUsesForwardedForOnlyWhenTrusted | PASSED | 0.054 |
| RelayTest | oversizedFrameIsTooLarge | PASSED | 0.043 |
| RelayTest | messageRateLimit | PASSED | 0.03 |
| RelayTest | pingPong | PASSED | 0.03 |
| RelayTest | invalidRoomIdRejectedBeforeUpgrade | PASSED | 1.054 |
| RelayTest | peerLeftAndRoomFreed | PASSED | 0.045 |
| RelayTest | healthz | PASSED | 0.013 |
| RelayTest | idleRoomsAreClosed | PASSED | 0.525 |
| RelayTest | simultaneousJoinsAreSerialized | PASSED | 0.025 |
| RelayTest | signalRelayedVerbatim | PASSED | 0.041 |
| RelayTest | signalWithoutPeerIsNoPeer | PASSED | 0.023 |
| RelayTest | plainGetWithoutUpgrade | PASSED | 0.019 |
| UnitTest | shortHashIsShort | PASSED | 0.0 |
| UnitTest | keyedLimiterPrunesIdleKeys | PASSED | 0.002 |
| UnitTest | configFromEnv | PASSED | 0.036 |
| UnitTest | parsing | PASSED | 0.001 |
| UnitTest | slidingWindow | PASSED | 0.0 |
