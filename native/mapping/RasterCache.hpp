#pragma once
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace r2c::mapping {
// Serial-worker, read-only raster cache. A returned pointer is valid until the
// next get/clear call; callers must finish sampling before opening another image.
class RasterCache {
 struct Raster {
  const uint32_t* data=nullptr;size_t bytes=0;uint64_t used=0;
  ~Raster(){if(data)munmap(const_cast<uint32_t*>(data),bytes);}
 };
 std::map<std::string,std::unique_ptr<Raster>> entries;
 size_t budget,limit,total=0;uint64_t clock=0;
 void evict(){auto oldest=entries.begin();for(auto it=entries.begin();it!=entries.end();++it)if(it->second->used<oldest->second->used)oldest=it;total-=oldest->second->bytes;entries.erase(oldest);}
public:
 explicit RasterCache(size_t bytes=256*1024*1024,size_t count=8):budget(bytes),limit(count){if(!budget||!limit)throw std::invalid_argument("Invalid raster cache budget.");}
 size_t mappedBytes()const{return total;}
 size_t mappedCount()const{return entries.size();}
 void clear(){entries.clear();total=0;}
 const uint32_t* get(const std::string& path,int width,int height){
  if(width<=0||height<=0||size_t(width)>budget/4/size_t(height))throw std::runtime_error("Local image exceeds raster cache budget.");
  const size_t bytes=size_t(width)*height*4;
  auto found=entries.find(path);
  if(found!=entries.end()){if(found->second->bytes!=bytes)throw std::runtime_error("Local image dimensions changed.");found->second->used=++clock;return found->second->data;}
  const int fd=open(path.c_str(),O_RDONLY);struct stat info{};
  if(fd<0)throw std::runtime_error("Cannot open local image pixels: "+std::string(std::strerror(errno)));
  if(fstat(fd,&info)||info.st_size<0||size_t(info.st_size)!=bytes){close(fd);throw std::runtime_error("Invalid local image pixels.");}
  while(!entries.empty()&&(total+bytes>budget||entries.size()>=limit))evict();
  auto entry=std::make_unique<Raster>();
  void* data=mmap(nullptr,bytes,PROT_READ,MAP_PRIVATE,fd,0);int error=errno;
  // Release cached mappings and retry once if the OS is short of address space.
  if(data==MAP_FAILED&&!entries.empty()){clear();data=mmap(nullptr,bytes,PROT_READ,MAP_PRIVATE,fd,0);error=errno;}
  close(fd); // mmap retains the backing object; do not retain hundreds of descriptors.
  if(data==MAP_FAILED)throw std::runtime_error("Cannot map local image pixels: "+std::string(std::strerror(error)));
  entry->data=static_cast<const uint32_t*>(data);entry->bytes=bytes;entry->used=++clock;
  const auto* result=entry->data;entries.emplace(path,std::move(entry));total+=bytes;return result;
 }
};
}
