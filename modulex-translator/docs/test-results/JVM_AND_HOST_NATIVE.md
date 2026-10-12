# Test results (host run, 2026-10-12)

Command: `./gradlew :core:test :engines:test :signaling:test --rerun-tasks`
Host: Intel Xeon 2.80 GHz, 4 vCPU, Linux x86_64, OpenJDK 21. Raw JUnit XML is regenerated under `*/build/test-results/test/`.

**Totals: 91 passed, 0 failed, 1 skipped** (skipped: real-model corpus test, needs `-Pmodulex.testModel`).

## :core

| Suite | Test | Result | Time (s) |
|---|---|---|---|
| AssetsTest | productionSourceRefusesPlainHttp | PASSED | 0.186 |
| AssetsTest | insufficientStorageIsReportedBeforeDownloading | PASSED | 0.178 |
| AssetsTest | manifestValidationRejectsUnsafeEntries | PASSED | 0.14 |
| AssetsTest | interruptedDownloadResumesWithRange | PASSED | 0.316 |
| AssetsTest | installsVerifiesAndReportsStatus | PASSED | 0.184 |
| AssetsTest | importChecksPinnedHashOrRecordsUserImport | PASSED | 0.163 |
| AssetsTest | corruptionAfterInstallIsDetected | PASSED | 0.357 |
| AssetsTest | packsInUseCannotBeDeletedOrReplaced | PASSED | 0.234 |
| AssetsTest | corruptedDownloadIsRejectedAndNotMarkedInstalled | PASSED | 0.204 |
| AssetsTest | unpinnedPacksAreNeverDownloaded | PASSED | 0.058 |
| AssetsTest | ggufHeaderIsParsed | PASSED | 0.074 |
| ShippedManifestTest | shippedManifestIsValidAndHonest | PASSED | 0.058 |
| ConversationSessionTest | wrongSecretIsRejectedByHost | PASSED | 1.183 |
| ConversationSessionTest | handshakeAuthenticatesAndDeliversBothDirections | PASSED | 0.574 |
| ConversationSessionTest | aDifferentGuestCannotTakeOverAnEstablishedSession | PASSED | 0.204 |
| ConversationSessionTest | languageUpdatesPropagate | PASSED | 0.14 |
| ConversationSessionTest | reconnectReplaysUnacknowledgedMessagesWithoutDuplicates | PASSED | 0.271 |
| LoopbackLatencyTest | deliveryLatencyOverLoopbackTls | PASSED | 0.588 |
| LoopbackLatencyTest | output | `LOOPBACK (host JVM, not a device): connect+TLS+auth=61.0 ms; delivery median=1.516 ms p95=4.602 ms max=14.482 ms n=200` | |
| LanguageTest | thermalPolicyReducesWork | PASSED | 0.002 |
| LanguageTest | evictionKeepsTranslationResidentWhenPossible | PASSED | 0.002 |
| LanguageTest | uniqueScriptDetectionNeverGuessesSharedScripts | PASSED | 0.013 |
| LanguageTest | readinessRequiresInstalledVerifiedPacks | PASSED | 0.004 |
| LanguageTest | registryIsConsistent | PASSED | 0.001 |
| LanguageTest | scriptDetectionPicksSideOfPairOnlyWhenReliable | PASSED | 0.002 |
| ProtocolTest | codecRejectsMalformedUnknownAndOversized | PASSED | 0.017 |
| ProtocolTest | codecRoundTripsUnicode | PASSED | 0.001 |
| ProtocolTest | reliableTypesAreSequenced | PASSED | 0.0 |
| ProtocolTest | validatorAcceptsWellFormedMessages | PASSED | 0.0 |
| ProtocolTest | validatorRejectsBadMessages | PASSED | 0.002 |
| ReliabilityTest | recentIdsIsBounded | PASSED | 0.004 |
| ReliabilityTest | sequencerDeliversInOrderExactlyOnce | PASSED | 0.003 |
| ReliabilityTest | retriesThenGivesUp | PASSED | 0.001 |
| ReliabilityTest | queueAssignsSequenceAndAcknowledgesCumulatively | PASSED | 0.002 |
| PairingTest | shortAuthStringIsSixDigitsAndStable | PASSED | 0.001 |
| PairingTest | proofsBindSessionParticipantsAndFingerprints | PASSED | 0.001 |
| PairingTest | rejectsExpiredForeignAndIncompletePayloads | PASSED | 0.014 |
| PairingTest | qrPayloadRoundTrips | PASSED | 0.006 |
| PairingTest | pairingCodesNormaliseAndDeriveDeterministically | PASSED | 0.821 |
| PairingTest | idsAreUniqueAndUrlSafe | PASSED | 0.025 |
| SpeechTest | twoPhrasesBecomeTwoUtterances | PASSED | 0.026 |
| SpeechTest | ignoresBackgroundNoiseAndShortClicks | PASSED | 0.021 |
| SpeechTest | pushToTalkFlushEndsUtterance | PASSED | 0.008 |
| SpeechTest | detectsOneUtteranceSurroundedBySilence | PASSED | 0.025 |
| SpeechTest | longSpeechIsForceSplitAndBounded | PASSED | 0.036 |
| SpeechTest | overlapMergeRemovesDuplicatedBoundaryWords | PASSED | 0.003 |
| SpeechTest | nonSpeechMarkersAreDetected | PASSED | 0.0 |
| TranslationTest | sanitizerStripsThinkingBlocksOnly | PASSED | 0.005 |
| TranslationTest | paragraphsAndLayoutArePreserved | PASSED | 0.026 |
| TranslationTest | promptUsesDocumentedTemplates | PASSED | 0.002 |
| TranslationTest | longParagraphSplitsAtSentenceBoundariesWithinLimit | PASSED | 0.007 |
| TranslationTest | joinUsesTranslatedPiecesAndOriginalSeparators | PASSED | 0.001 |
| TranslationTest | unpunctuatedTextFallsBackToWordsWithoutBreakingSurrogates | PASSED | 0.006 |
| TranslationTest | arabicAndCjkPunctuationAreBoundaries | PASSED | 0.005 |
| TranslationTest | blankInputHasNoPieces | PASSED | 0.005 |
| TranslationTest | shortTextIsOnePiece | PASSED | 0.0 |
| TlsTransportTest | framingRejectsOversizedAndTruncatedFrames | PASSED | 0.111 |
| TlsTransportTest | wrongPinnedFingerprintIsRejected | PASSED | 0.168 |
| TlsTransportTest | tlsOverRealLoopbackTcp | PASSED | 0.157 |
| TlsTransportTest | handshakeOverPipesExchangesFramesBothWays | PASSED | 0.285 |
| TlsTransportTest | guestWithoutClientCertificateIsRejected | PASSED | 0.085 |

## :engines

| Suite | Test | Result | Time (s) |
|---|---|---|---|
| OcrEngineTest | blankImageYieldsNoText | PASSED | 0.543 |
| OcrEngineTest | recognisesArabicText | PASSED | 0.54 |
| OcrEngineTest | invalidInputsAreRejectedWithoutCrashing | PASSED | 0.24 |
| OcrEngineTest | recognisesMixedArabicEnglishPage | PASSED | 0.539 |
| OcrEngineTest | recognisesEnglishText | PASSED | 0.193 |
| OcrEngineTest | output | `OCR ara: raw-cer=0.660 pipeline-cer=0.000 scale=0.50 passes=3 conf=92.1 time=158ms text= مرحبا بكم فيالمطعم |  الرجاء الانتظار حتى يتم تقديمالطعام` | |
| OcrEngineTest | output | `OCR mixed: cer=0.261 scale=0.50 conf=95.2 time=372ms text=Exit |  مخرجالطوارئ | Pharmacy open 24 hours` | |
| OcrEngineTest | output | `known limitation reproduced: Arabic line 'صيدلية مفتوحة' not recognised` | |
| OcrEngineTest | output | `OCR eng: cer=0.000 conf=96.4 time=74ms text=The quick brown fox jumps over the lazy dog. | Platform 4 departs at 10:35 for Cairo.` | |
| TranslationEngineTest | rejectsNonGgufAndWrongArchitectureBeforeNativeLoad | PASSED | 0.045 |
| TranslationEngineTest | realModelTranslatesCorpus | SKIPPED | 0.012 |
| TranslationEngineTest | nativeLoaderRejectsTruncatedModelWithoutCrashing | PASSED | 0.011 |
| TranslationEngineTest | pipelineRunsEndToEndWithFixtureModel | PASSED | 0.118 |

## :signaling

| Suite | Test | Result | Time (s) |
|---|---|---|---|
| RelayTest | joinRateLimitPerIp | PASSED | 2.398 |
| RelayTest | serverFull | PASSED | 0.118 |
| RelayTest | twoPeersJoin | PASSED | 0.034 |
| RelayTest | thirdParticipantGetsRoomFull | PASSED | 0.06 |
| RelayTest | badFramesThenClose | PASSED | 0.053 |
| RelayTest | binaryFrameRejected | PASSED | 0.031 |
| RelayTest | joinRateLimitUsesForwardedForOnlyWhenTrusted | PASSED | 0.041 |
| RelayTest | oversizedFrameIsTooLarge | PASSED | 0.031 |
| RelayTest | messageRateLimit | PASSED | 0.024 |
| RelayTest | pingPong | PASSED | 0.031 |
| RelayTest | invalidRoomIdRejectedBeforeUpgrade | PASSED | 1.056 |
| RelayTest | peerLeftAndRoomFreed | PASSED | 0.044 |
| RelayTest | healthz | PASSED | 0.015 |
| RelayTest | idleRoomsAreClosed | PASSED | 0.523 |
| RelayTest | simultaneousJoinsAreSerialized | PASSED | 0.028 |
| RelayTest | signalRelayedVerbatim | PASSED | 0.033 |
| RelayTest | signalWithoutPeerIsNoPeer | PASSED | 0.018 |
| RelayTest | plainGetWithoutUpgrade | PASSED | 0.014 |
| UnitTest | shortHashIsShort | PASSED | 0.001 |
| UnitTest | keyedLimiterPrunesIdleKeys | PASSED | 0.001 |
| UnitTest | configFromEnv | PASSED | 0.036 |
| UnitTest | parsing | PASSED | 0.002 |
| UnitTest | slidingWindow | PASSED | 0.001 |
