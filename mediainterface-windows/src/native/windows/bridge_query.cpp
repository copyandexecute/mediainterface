#include "bridge_shared.h"

#include <wincodec.h>

#include <algorithm>
#include <cmath>
#include <sstream>
#include <unordered_set>
#include <vector>

// Decodes the thumbnail bytes (PNG/JPEG/...), downscales so the longest edge is
// <= maxSize (aspect preserved; never upscales), and re-encodes as PNG. Returns
// true and fills `out` on success; false leaves the caller to use the source
// bytes. Pure WIC + classic COM — relies on the process MTA created in
// nativeInit (executor threads join it implicitly).
static bool downscale_thumbnail_to_png(BYTE* data, UINT dataLen, UINT maxSize, std::vector<uint8_t>& out) {
    try {
        com_ptr<IWICImagingFactory> factory;
        if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                    IID_PPV_ARGS(factory.put())))) {
            return false;
        }

        com_ptr<IWICStream> inStream;
        if (FAILED(factory->CreateStream(inStream.put()))) return false;
        if (FAILED(inStream->InitializeFromMemory(data, dataLen))) return false;

        com_ptr<IWICBitmapDecoder> decoder;
        if (FAILED(factory->CreateDecoderFromStream(inStream.get(), nullptr,
                                                    WICDecodeMetadataCacheOnDemand, decoder.put()))) {
            return false;
        }
        com_ptr<IWICBitmapFrameDecode> frame;
        if (FAILED(decoder->GetFrame(0, frame.put()))) return false;

        UINT w = 0, h = 0;
        if (FAILED(frame->GetSize(&w, &h)) || w == 0 || h == 0) return false;

        UINT longest = (w > h) ? w : h;
        double scale = (maxSize > 0 && longest > maxSize) ? static_cast<double>(maxSize) / longest : 1.0;
        UINT nw = static_cast<UINT>(std::lround(w * scale));
        UINT nh = static_cast<UINT>(std::lround(h * scale));
        if (nw < 1) nw = 1;
        if (nh < 1) nh = 1;

        com_ptr<IWICFormatConverter> converter;
        if (FAILED(factory->CreateFormatConverter(converter.put()))) return false;
        if (FAILED(converter->Initialize(frame.get(), GUID_WICPixelFormat32bppBGRA,
                                         WICBitmapDitherTypeNone, nullptr, 0.0,
                                         WICBitmapPaletteTypeCustom))) {
            return false;
        }

        // IWICFormatConverter and IWICBitmapScaler both derive from
        // IWICBitmapSource, so their raw pointers upcast directly (no QI needed).
        // scaler is declared here so it outlives the WriteSource call below.
        com_ptr<IWICBitmapScaler> scaler;
        IWICBitmapSource* source = converter.get();
        if (scale < 1.0) {
            if (FAILED(factory->CreateBitmapScaler(scaler.put()))) return false;
            if (FAILED(scaler->Initialize(converter.get(), nw, nh, WICBitmapInterpolationModeFant))) return false;
            source = scaler.get();
        }

        com_ptr<IStream> outStream;
        if (FAILED(CreateStreamOnHGlobal(nullptr, TRUE, outStream.put()))) return false;
        com_ptr<IWICBitmapEncoder> encoder;
        if (FAILED(factory->CreateEncoder(GUID_ContainerFormatPng, nullptr, encoder.put()))) return false;
        if (FAILED(encoder->Initialize(outStream.get(), WICBitmapEncoderNoCache))) return false;

        com_ptr<IWICBitmapFrameEncode> frameEncode;
        com_ptr<IPropertyBag2> encoderProps;
        if (FAILED(encoder->CreateNewFrame(frameEncode.put(), encoderProps.put()))) return false;
        if (FAILED(frameEncode->Initialize(encoderProps.get()))) return false;
        if (FAILED(frameEncode->SetSize(nw, nh))) return false;
        WICPixelFormatGUID pixelFormat = GUID_WICPixelFormat32bppBGRA;
        if (FAILED(frameEncode->SetPixelFormat(&pixelFormat))) return false;
        if (FAILED(frameEncode->WriteSource(source, nullptr))) return false;
        if (FAILED(frameEncode->Commit())) return false;
        if (FAILED(encoder->Commit())) return false;

        STATSTG stat{};
        if (FAILED(outStream->Stat(&stat, STATFLAG_NONAME))) return false;
        ULONG size = static_cast<ULONG>(stat.cbSize.QuadPart);
        if (size == 0) return false;
        LARGE_INTEGER origin{};
        if (FAILED(outStream->Seek(origin, STREAM_SEEK_SET, nullptr))) return false;
        out.resize(size);
        ULONG read = 0;
        if (FAILED(outStream->Read(out.data(), size, &read)) || read != size) return false;
        return true;
    } catch (...) {
        return false;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionIds(JNIEnv* env, jclass clazz) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetSessionIds enter");
    try {
        trace_native(env, "nativeGetSessionIds requesting manager");
        auto manager = request_manager_safe(env);
        if (!manager.has_value()) {
            trace_native(env, "nativeGetSessionIds manager unavailable");
            return new_string_array(env, {});
        }
        std::vector<std::string> ids;
        std::unordered_set<std::string> seen;
        for (auto const& session : manager.value().GetSessions()) {
            std::string id = to_string(session.SourceAppUserModelId());
            if (id.empty()) {
                continue;
            }
            if (seen.insert(id).second) {
                ids.push_back(id);
            }
        }
        trace_native(env, std::string("nativeGetSessionIds count=") + std::to_string(ids.size()));
        return new_string_array(env, ids);
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetSessionIds", e);
        return new_string_array(env, {});
    } catch (...) {
        trace_native(env, "nativeGetSessionIds unknown exception");
        return new_string_array(env, {});
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetSessionAppName(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetSessionAppName enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            trace_native(env, "nativeGetSessionAppName no session");
            return nullptr;
        }
        std::string appId = to_string(session.value().SourceAppUserModelId());
        trace_native(env, std::string("nativeGetSessionAppName len=") + std::to_string(appId.size()));
        return to_jstring(env, appId);
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetSessionAppName", e);
        return nullptr;
    } catch (...) {
        trace_native(env, "nativeGetSessionAppName unknown exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeIsSessionActive(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeIsSessionActive enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            return JNI_FALSE;
        }
        auto status = session.value().GetPlaybackInfo().PlaybackStatus();
        bool active = status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing ||
                      status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused;
        trace_native(env, std::string("nativeIsSessionActive -> ") + (active ? "true" : "false"));
        return active ? JNI_TRUE : JNI_FALSE;
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeIsSessionActive", e);
        return JNI_FALSE;
    } catch (...) {
        trace_native(env, "nativeIsSessionActive unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetPlaybackState(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetPlaybackState enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            return 3;
        }
        auto status = session.value().GetPlaybackInfo().PlaybackStatus();
        if (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing) {
            return 0;
        }
        if (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused) {
            return 1;
        }
        if (status == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Stopped) {
            return 2;
        }
        return 3;
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetPlaybackState", e);
        return 3;
    } catch (...) {
        trace_native(env, "nativeGetPlaybackState unknown exception");
        return 3;
    }
}

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetCapabilities(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetCapabilities enter");
    jboolean defaults[6] = {JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE};
    jbooleanArray array = env->NewBooleanArray(6);
    if (array == nullptr) {
        trace_native(env, "nativeGetCapabilities NewBooleanArray failed");
        return nullptr;
    }
    env->SetBooleanArrayRegion(array, 0, 6, defaults);

    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            return array;
        }
        auto controls = session.value().GetPlaybackInfo().Controls();
        jboolean values[6] = {
            static_cast<jboolean>(controls.IsPlayEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsPauseEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsNextEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsPreviousEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsStopEnabled() ? JNI_TRUE : JNI_FALSE),
            static_cast<jboolean>(controls.IsPlaybackPositionEnabled() ? JNI_TRUE : JNI_FALSE)
        };
        env->SetBooleanArrayRegion(array, 0, 6, values);
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetCapabilities", e);
    } catch (...) {
        trace_native(env, "nativeGetCapabilities unknown exception");
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetNowPlaying(JNIEnv* env, jclass clazz, jstring sessionId) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetNowPlaying enter");
    std::vector<std::string> payload(8, "");
    try {
        auto id = to_utf8(sessionId, env);
        auto session = find_session(id, env);
        if (!session.has_value()) {
            trace_native(env, "nativeGetNowPlaying no session");
            return new_string_array(env, payload);
        }

        trace_native(env, "nativeGetNowPlaying fetching media properties");
        auto mediaProps = session.value().TryGetMediaPropertiesAsync().get();
        payload[0] = to_string(mediaProps.Title());
        payload[1] = to_string(mediaProps.Artist());
        payload[2] = to_string(mediaProps.AlbumTitle());
        trace_native(env, std::string("nativeGetNowPlaying strings lens t/a/al=")
                + std::to_string(payload[0].size()) + "/"
                + std::to_string(payload[1].size()) + "/"
                + std::to_string(payload[2].size()));

        // Artwork is intentionally NOT read here. Decoding the thumbnail stream
        // and base64-encoding it (up to a few MB) on every poll churned the
        // heap 5x/sec, and the resulting GC pauses showed up as periodic
        // freezes / FPS drops while the overlay was on. Callers fetch artwork
        // separately via nativeGetArtwork, only when the track actually changes.
        // payload[3] stays empty here.

        std::ostringstream metadata;
        std::string albumArtist = to_string(mediaProps.AlbumArtist());
        if (!albumArtist.empty()) {
            metadata << "albumArtist=" << albumArtist << "\n";
        }
        if (mediaProps.TrackNumber() > 0) {
            metadata << "trackNumber=" << mediaProps.TrackNumber() << "\n";
        }
        std::vector<std::string> genres;
        for (auto const& genre : mediaProps.Genres()) {
            std::string value = to_string(genre);
            if (!value.empty()) {
                genres.push_back(value);
            }
        }
        if (!genres.empty()) {
            metadata << "genre=";
            for (size_t i = 0; i < genres.size(); ++i) {
                if (i > 0) {
                    metadata << ", ";
                }
                metadata << genres[i];
            }
            metadata << "\n";
        }

        trace_native(env, "nativeGetNowPlaying fetching timeline");
        auto timeline = session.value().GetTimelineProperties();
        int64_t startTicks = timeline.StartTime().count();
        int64_t endTicks = timeline.EndTime().count();
        int64_t minSeekTicks = timeline.MinSeekTime().count();
        int64_t maxSeekTicks = timeline.MaxSeekTime().count();
        int64_t rawPositionTicks = timeline.Position().count();
        auto playbackInfo = session.value().GetPlaybackInfo();
        auto playbackStatus = playbackInfo.PlaybackStatus();
        auto playbackRateRef = playbackInfo.PlaybackRate();
        double playbackRate = playbackRateRef ? playbackRateRef.Value() : 1.0;

        int64_t lastUpdatedTicks = timeline.LastUpdatedTime().time_since_epoch().count();
        int64_t nowTicks = winrt::clock::now().time_since_epoch().count();
        int64_t positionTicks = rawPositionTicks;
        if (playbackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing
                && playbackRate != 0.0
                && nowTicks > lastUpdatedTicks) {
            double deltaTicks = static_cast<double>(nowTicks - lastUpdatedTicks) * playbackRate;
            positionTicks += static_cast<int64_t>(deltaTicks);
        }
        if (positionTicks < 0) {
            positionTicks = 0;
        }
        if (endTicks > 0 && positionTicks > endTicks) {
            positionTicks = endTicks;
        }

        int64_t positionMs = ticks_to_millis(positionTicks);
        int64_t durationMs = ticks_to_millis(endTicks);
        payload[4] = durationMs > 0 ? std::to_string(durationMs) : "";
        payload[5] = positionMs >= 0 ? std::to_string(positionMs) : "";
        payload[6] = durationMs <= 0 ? "true" : "false";

        metadata << "timelineStartMs=" << ticks_to_millis(startTicks) << "\n";
        metadata << "timelineEndMs=" << ticks_to_millis(endTicks) << "\n";
        metadata << "timelineMinSeekMs=" << ticks_to_millis(minSeekTicks) << "\n";
        metadata << "timelineMaxSeekMs=" << ticks_to_millis(maxSeekTicks) << "\n";
        metadata << "timelineRawPositionMs=" << ticks_to_millis(rawPositionTicks) << "\n";
        metadata << "timelineLastUpdatedTicks=" << lastUpdatedTicks << "\n";
        metadata << "timelineNowTicks=" << nowTicks << "\n";
        if (playbackRateRef) {
            metadata << "playbackRate=" << playbackRateRef.Value() << "\n";
        } else {
            metadata << "playbackRate=null\n";
        }
        metadata << "playbackStatus=" << static_cast<int>(playbackStatus) << "\n";
        payload[7] = metadata.str();
        trace_native(env, "nativeGetNowPlaying payload assembled");
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetNowPlaying", e);
    } catch (...) {
        trace_native(env, "nativeGetNowPlaying unknown exception");
    }
    trace_native(env, "nativeGetNowPlaying returning payload");
    return new_string_array(env, payload);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_endlesssource_mediainterface_windows_WinRtBridge_nativeGetArtwork(JNIEnv* env, jclass clazz, jstring sessionId, jint maxSize) {
    ensure_trace_bridge(env, clazz);
    trace_native(env, "nativeGetArtwork enter");
    try {
        auto session = find_session(to_utf8(sessionId, env), env);
        if (!session.has_value()) {
            trace_native(env, "nativeGetArtwork no session");
            return nullptr;
        }
        auto mediaProps = session.value().TryGetMediaPropertiesAsync().get();
        auto thumbnail = mediaProps.Thumbnail();
        if (!thumbnail) {
            trace_native(env, "nativeGetArtwork no thumbnail");
            return nullptr;
        }
        auto stream = thumbnail.OpenReadAsync().get();
        uint32_t size = static_cast<uint32_t>(std::min<uint64_t>(stream.Size(), static_cast<uint64_t>(5 * 1024 * 1024)));
        if (size == 0) {
            trace_native(env, "nativeGetArtwork empty stream");
            return nullptr;
        }
        trace_native(env, std::string("nativeGetArtwork reading thumbnail size=") + std::to_string(size));
        auto buffer = stream.ReadAsync(Buffer(size), size, InputStreamOptions::None).get();

        // Downscale before base64 so we don't ship a full-res cover for a
        // sprite that renders ~128px. Any WIC failure falls back to source bytes.
        if (maxSize > 0) {
            com_array<uint8_t> source;
            CryptographicBuffer::CopyToByteArray(buffer, source);
            std::vector<uint8_t> scaled;
            if (downscale_thumbnail_to_png(source.data(), static_cast<UINT>(source.size()),
                                           static_cast<UINT>(maxSize), scaled)) {
                trace_native(env, std::string("nativeGetArtwork downscaled bytes ")
                        + std::to_string(source.size()) + " -> " + std::to_string(scaled.size()));
                auto scaledBuffer = CryptographicBuffer::CreateFromByteArray(
                        array_view<uint8_t const>(scaled.data(), scaled.data() + scaled.size()));
                return to_jstring(env, to_string(CryptographicBuffer::EncodeToBase64String(scaledBuffer)));
            }
            trace_native(env, "nativeGetArtwork downscale failed, using source bytes");
        }
        return to_jstring(env, to_string(CryptographicBuffer::EncodeToBase64String(buffer)));
    } catch (const hresult_error& e) {
        trace_hresult(env, "nativeGetArtwork", e);
        return nullptr;
    } catch (...) {
        trace_native(env, "nativeGetArtwork unknown exception");
        return nullptr;
    }
}
