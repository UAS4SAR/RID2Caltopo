#include "probe.h"
#include "openMVG/sfm/sfm_data.hpp"
#include "openMVG/sfm/sfm_data_io.hpp"
#include <exception>
#include <fstream>
#include <string>
#include <vector>

int r2c_main_SfMInit_ImageListing(int, char **);
int r2c_main_ComputeFeatures(int, char **);
int r2c_main_ComputeMatches(int, char **);
int r2c_main_GeometricFilter(int, char **);
int r2c_main_SfM(int, char **);

static int invoke(int (*entry)(int, char **), std::vector<std::string> args) {
  std::vector<char *> pointers;
  for (auto &arg : args) pointers.push_back(&arg[0]);
  pointers.push_back(nullptr);
  return entry(static_cast<int>(args.size()), pointers.data());
}

extern "C" int r2c_openmvg_stage(int stage, const char *job_root) {
  if (!job_root || !*job_root) return 64;
  const std::string root(job_root), matches(root + "/matches"), output(root + "/reconstruction");
  try {
    switch (stage) {
    case 0:
      // Fixed thermal focal prior; this is not a calibrated camera model.
      return invoke(r2c_main_SfMInit_ImageListing, {"init", "-i", root + "/images",
        "-o", matches, "-f", "1884.444444", "-c", "1", "-P"});
    case 1:
      return invoke(r2c_main_ComputeFeatures, {"features", "-i", matches + "/sfm_data.json",
        "-o", matches, "-m", "SIFT", "-p", "NORMAL"});
    case 2:
      return invoke(r2c_main_ComputeMatches, {"matches", "-i", matches + "/sfm_data.json",
        "-o", matches + "/matches.putative.bin", "-n", "BRUTEFORCEL2", "-c", "8"});
    case 3:
      return invoke(r2c_main_GeometricFilter, {"filter", "-i", matches + "/sfm_data.json",
        "-m", matches + "/matches.putative.bin", "-o", matches + "/matches.f.bin",
        "-g", "f", "-c", "8"});
    case 4:
      return invoke(r2c_main_SfM, {"sfm", "-i", matches + "/sfm_data.json", "-m", matches,
        "-M", matches + "/matches.f.bin", "-o", output, "-s", "INCREMENTAL",
        "-f", "NONE", "-P"});
    case 5: {
      openMVG::sfm::SfM_Data data;
      if (!openMVG::sfm::Load(data, output + "/sfm_data.bin", openMVG::sfm::ALL)) return 65;
      if (!openMVG::sfm::Save(data, output + "/sfm_data.json", openMVG::sfm::ALL)) return 66;
      std::ofstream report(root + "/result.json");
      report << "{\"engine\":\"OpenMVG\",\"engineVersion\":\"2.1\",\"inputViews\":"
             << data.views.size() << ",\"registeredCameras\":" << data.poses.size()
             << ",\"sparsePoints\":" << data.structure.size()
             << ",\"denseReconstructionComplete\":false,\"mapLayerReady\":false}\n";
      return report && data.poses.size() >= 3 && !data.structure.empty() ? 0 : 67;
    }
    default: return 64;
    }
  } catch (const std::exception &) { return 70; }
}
