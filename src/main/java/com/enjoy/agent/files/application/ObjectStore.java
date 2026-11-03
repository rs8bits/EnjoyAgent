package com.enjoy.agent.files.application;

import java.io.InputStream;

/** Object contents stay outside database transactions and are always streamed. */
public interface ObjectStore {
  String bucket();

  void put(String key, InputStream content, long size, String mediaType);

  InputStream open(String bucket, String key);

  void remove(String bucket, String key);
}
