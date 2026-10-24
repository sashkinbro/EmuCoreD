// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <regex>
#include <cctype>

struct TypedCheatInput { std::string format; std::vector<std::string> lines; };
inline bool typedCheatInputs(JNIEnv* env, jobjectArray formats, jobjectArray codes,
                            std::vector<TypedCheatInput>& result) {
  if (!formats || !codes || env->GetArrayLength(formats) != env->GetArrayLength(codes) || env->GetArrayLength(codes)>4096) return false;
  size_t total=0;
  for(jsize i=0;i<env->GetArrayLength(codes);++i) {
    auto f=static_cast<jstring>(env->GetObjectArrayElement(formats,i));
    auto c=static_cast<jstring>(env->GetObjectArrayElement(codes,i));
    if(!f || !c) { if(f)env->DeleteLocalRef(f); if(c)env->DeleteLocalRef(c); return false; }
    const char* fp=env->GetStringUTFChars(f,nullptr); const char* cp=env->GetStringUTFChars(c,nullptr);
    if(!fp || !cp) { if(fp)env->ReleaseStringUTFChars(f,fp); if(cp)env->ReleaseStringUTFChars(c,cp); env->DeleteLocalRef(f);env->DeleteLocalRef(c);return false; }
    TypedCheatInput entry; entry.format=fp; std::string raw=cp;
    env->ReleaseStringUTFChars(f,fp);env->ReleaseStringUTFChars(c,cp);env->DeleteLocalRef(f);env->DeleteLocalRef(c);
    total+=raw.size(); if(raw.empty() || raw.size()>65536 || total>1048576 || entry.format.size()>64) return false;
    std::istringstream stream(raw); std::string line;
    while(std::getline(stream,line)) {
      const auto first=line.find_first_not_of(" \t\r"); if(first==std::string::npos)continue;
      line=line.substr(first,line.find_last_not_of(" \t\r")-first+1);
      for(auto& ch:line)ch=std::toupper(static_cast<unsigned char>(ch));
      entry.lines.push_back(std::move(line));
    }
    if(entry.lines.empty()) return false;
    result.push_back(std::move(entry));
  }
  return true;
}
inline bool cheatLineMatches(const std::string& line,const char* pattern) {
  return std::regex_match(line,std::regex(pattern));
}
