.PHONY: all clean

COMPOSITE_NAME = KafkaBlobSample
SPL_NAMESPACE = com.ibm.streamsx.kafka.sample
SPL_MAIN_COMPOSITE = $(SPL_NAMESPACE)::$(COMPOSITE_NAME)
OUTPUT_DIR = output/$(SPL_NAMESPACE).$(COMPOSITE_NAME)

SPLC_FLAGS = -a
SPLC = $(STREAMS_INSTALL)/bin/sc
SPL_PATH = ../..:$(STREAMS_INSTALL)/toolkits/com.ibm.streamsx.kafka

all:
	if [ -x ../../gradlew ]; then \
	  ../../gradlew build; \
	else \
	  $(SPLC) $(SPLC_FLAGS) -M $(SPL_MAIN_COMPOSITE) --output-directory $(OUTPUT_DIR) -t $(SPL_PATH); \
	fi

clean:
	if [ -x ../../gradlew ]; then \
	  ../../gradlew clean; \
	else \
	  $(SPLC) $(SPLC_FLAGS) -C -M $(SPL_MAIN_COMPOSITE) --output-directory $(OUTPUT_DIR); \
	fi
