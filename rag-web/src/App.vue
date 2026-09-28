<template>
  <BootSequence />
  <router-view v-slot="{ Component, route }">
    <MainLayout v-if="route.meta.requiresAuth">
      <component :is="Component" />
    </MainLayout>
    <component v-else :is="Component" />
  </router-view>
</template>

<script setup>
import MainLayout from './layouts/MainLayout.vue'
import BootSequence from './components/BootSequence.vue'
</script>

<style>
</style>
