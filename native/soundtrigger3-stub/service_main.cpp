// Minimal stub service for android.hardware.soundtrigger3 (V4).
// NOT part of AOSP: AOSP (android-17.0.0_r1) ships no default soundtrigger3 service.
// Every operation reports OPERATION_NOT_SUPPORTED; replace with a real HAL for hardware use.
#include <aidl/android/hardware/soundtrigger3/BnSoundTriggerHw.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <aidl/android/media/soundtrigger/Status.h>

using namespace aidl::android::hardware::soundtrigger3;
using namespace aidl::android::media::soundtrigger;
using ndk::ScopedAStatus;


namespace {
ScopedAStatus unsupported() {
    return ScopedAStatus::fromServiceSpecificError(static_cast<int32_t>(Status::OPERATION_NOT_SUPPORTED));
}
class StubSoundTriggerHw : public BnSoundTriggerHw {
  public:
    ScopedAStatus getProperties(Properties* out) override {
        out->implementor = "stub"; out->description = "soundtrigger3 stub (no engine)";
        out->version = 1; out->maxSoundModels = 0; out->maxKeyPhrases = 0; out->maxUsers = 0;
        out->recognitionModes = 0; out->captureTransition = false; out->maxBufferMs = 0;
        out->concurrentCapture = false; out->triggerInEvent = false; out->powerConsumptionMw = 0;
        return ScopedAStatus::ok();
    }
    ScopedAStatus registerGlobalCallback(const std::shared_ptr<ISoundTriggerHwGlobalCallback>&) override { return ScopedAStatus::ok(); }
    ScopedAStatus loadSoundModel(const SoundModel&, const std::shared_ptr<ISoundTriggerHwCallback>&, int32_t*) override { return unsupported(); }
    ScopedAStatus loadPhraseSoundModel(const PhraseSoundModel&, const std::shared_ptr<ISoundTriggerHwCallback>&, int32_t*) override { return unsupported(); }
    ScopedAStatus unloadSoundModel(int32_t) override { return unsupported(); }
    ScopedAStatus startRecognition(int32_t, int32_t, int32_t, const RecognitionConfig&) override { return unsupported(); }
    ScopedAStatus stopRecognition(int32_t) override { return unsupported(); }
    ScopedAStatus forceRecognitionEvent(int32_t) override { return unsupported(); }
    ScopedAStatus queryParameter(int32_t, ModelParameter, std::optional<ModelParameterRange>*) override { return unsupported(); }
    ScopedAStatus getParameter(int32_t, ModelParameter, int32_t*) override { return unsupported(); }
    ScopedAStatus setParameter(int32_t, ModelParameter, int32_t) override { return unsupported(); }
};
}  // namespace

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    auto hal = ndk::SharedRefBase::make<StubSoundTriggerHw>();
    const std::string name = std::string(ISoundTriggerHw::descriptor) + "/default";
    if (AServiceManager_addService(hal->asBinder().get(), name.c_str()) != STATUS_OK) return 1;
    ABinderProcess_joinThreadPool();
    return 1;
}
