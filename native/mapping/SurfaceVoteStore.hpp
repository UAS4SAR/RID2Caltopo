#pragma once
#include "SurfaceConsensus.hpp"
#include <cstdio>
#include <filesystem>
#include <functional>
#include <stdexcept>
#include <string>
#include <unistd.h>
namespace r2c::mapping {
// Spool all evidence to the region work directory. Only one 4,096-cell block is
// resolved at a time; the number of competing surfaces never limits evidence.
class SurfaceVoteStore {
 struct Record {uint32_t cell;float height;};
 static constexpr size_t blockCells=4096,bufferRecords=2048,maxOpen=16;
 struct Block {std::vector<Record> buffer;FILE* file=nullptr;size_t tick=0,records=0;};
 size_t size,pairs,tick=0,openCount=0;std::filesystem::path directory;std::vector<Block> blocks;
 void flush(size_t index){
  auto& b=blocks[index];if(b.buffer.empty())return;
  if(!b.file){
   if(openCount==maxOpen){auto victim=blocks.end();for(auto i=blocks.begin();i!=blocks.end();++i)if(i->file&&(victim==blocks.end()||i->tick<victim->tick))victim=i;
    if(std::fclose(victim->file)!=0){victim->file=nullptr;--openCount;throw std::runtime_error("Cannot save depth evidence.");}victim->file=nullptr;--openCount;}
   b.file=std::fopen((directory/(std::to_string(index)+".votes")).c_str(),"ab");if(!b.file)throw std::runtime_error("Cannot open depth evidence storage.");++openCount;
  }
  b.tick=++tick;if(std::fwrite(b.buffer.data(),sizeof(Record),b.buffer.size(),b.file)!=b.buffer.size())throw std::runtime_error("Not enough writable storage for depth evidence.");
  b.records+=b.buffer.size();b.buffer.clear();
 }
 public:
 SurfaceVoteStore(size_t cells,size_t pairCount,const std::filesystem::path& work):size(cells),pairs(pairCount),blocks((cells+blockCells-1)/blockCells){
  if(!cells||cells>3000000||pairCount>1152)throw std::runtime_error("Invalid depth evidence bounds.");
  auto pattern=(work/"depth-votes-XXXXXX").string();std::vector<char> path(pattern.begin(),pattern.end());path.push_back(0);
  if(!mkdtemp(path.data()))throw std::runtime_error("Cannot create depth evidence storage.");directory=path.data();
 }
 ~SurfaceVoteStore(){for(auto& b:blocks)if(b.file)std::fclose(b.file);std::error_code error;std::filesystem::remove_all(directory,error);}
 SurfaceVoteStore(const SurfaceVoteStore&)=delete;SurfaceVoteStore& operator=(const SurfaceVoteStore&)=delete;
 void add(size_t cell,float z){
  if(cell>=size)throw std::runtime_error("Invalid depth evidence cell.");if(!std::isfinite(z))return;
  size_t index=cell/blockCells;auto& b=blocks[index];b.buffer.push_back({uint32_t(cell%blockCells),z});if(b.buffer.size()==bufferRecords)flush(index);
 }
 std::vector<float> resolve(const std::function<void(double)>& progress){
  for(size_t i=0;i<blocks.size();++i){progress(.1*double(i)/blocks.size());flush(i);auto& b=blocks[i];if(b.file){int status=std::fclose(b.file);b.file=nullptr;--openCount;if(status)throw std::runtime_error("Cannot save depth evidence.");}}
  std::vector<float> result(size,std::numeric_limits<float>::quiet_NaN());
  for(size_t index=0;index<blocks.size();++index){progress(.1+.9*double(index)/blocks.size());auto& b=blocks[index];if(!b.records)continue;
   if(b.records>blockCells*pairs)throw std::runtime_error("Depth evidence exceeded its bounded pair count.");
   FILE* file=std::fopen((directory/(std::to_string(index)+".votes")).c_str(),"rb");if(!file)throw std::runtime_error("Cannot read depth evidence.");
   std::vector<Record> records(b.records);size_t read=std::fread(records.data(),sizeof(Record),records.size(),file);std::fclose(file);if(read!=records.size())throw std::runtime_error("Incomplete depth evidence.");
   std::sort(records.begin(),records.end(),[](const Record& a,const Record& b){return a.cell<b.cell||(a.cell==b.cell&&a.height<b.height);});
   std::vector<float> votes;for(size_t first=0;first<records.size();){size_t end=first;votes.clear();while(end<records.size()&&records[end].cell==records[first].cell)votes.push_back(records[end++].height);
    size_t cell=index*blockCells+records[first].cell;if(cell>=size||votes.size()>pairs)throw std::runtime_error("Invalid stored depth evidence.");result[cell]=supportedHeight(votes);first=end;
   }
   std::error_code error;std::filesystem::remove(directory/(std::to_string(index)+".votes"),error);
  }
  return result;
 }
};
}
